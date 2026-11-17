package com.videoeditor.app.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Jupiter (like the BlackHole app): makes a copy of a video without its sound and saves it to
 * the Gallery (Movies/Jupiter). The picture is copied as it is - not decoded and encoded again -
 * so quality, size, rotation, HDR and frame rate stay exactly the same and it is fast. Only if
 * the phone cannot copy that kind of video is the picture encoded again (Media3 Transformer).
 */
object SoundRemover {
    class Failed(message: String) : Exception(message)

    /** A saved silent copy. */
    data class Saved(val uri: Uri, val name: String, val reencoded: Boolean)

    const val FOLDER = "Jupiter"

    /** Tests: always take the encode-again way. */
    @Volatile
    var forceReencode = false

    /** Tests: a pause after every copied picture sample, so Stop can be pressed half way. */
    @Volatile
    var testDelayPerSampleMs = 0L

    /** Whether [source] has a sound track at all. */
    fun hasSound(context: Context, source: Uri): Boolean {
        val e = MediaExtractor()
        return try {
            e.setDataSource(context, source, null)
            (0 until e.trackCount).any { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } catch (ex: Exception) {
            false
        } finally {
            e.release()
        }
    }

    /** Removes the sound of [source] and saves the silent copy to the Gallery. */
    suspend fun removeSound(context: Context, source: Uri, onProgress: (Float) -> Unit = {}): Saved = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val extractor = MediaExtractor()
        val videoTrack: Int
        val durationUs: Long
        try {
            try {
                extractor.setDataSource(app, source, null)
            } catch (e: Exception) {
                throw Failed("This video cannot be opened.")
            }
            videoTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw Failed("This file has no picture.")
            if ((0 until extractor.trackCount).none { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }) {
                throw Failed("This video already has no sound.")
            }
            val f = extractor.getTrackFormat(videoTrack)
            durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
        } catch (e: Exception) {
            extractor.release()
            throw e
        }
        val base = MediaProbe.displayName(app, source).substringBeforeLast('.').ifBlank { "video" }.take(80)
        val name = "${base}_no_sound.mp4"
        val sourceSize = try {
            app.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            -1L
        }
        val free = Environment.getExternalStorageDirectory().usableSpace
        if (sourceSize > 0 && free < sourceSize + 64L * 1024 * 1024) {
            extractor.release()
            throw Failed("Not enough free storage for the copy. Free some space and try again.")
        }

        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val out = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        if (out == null) {
            extractor.release()
            throw Failed("The copy could not be created in the Gallery.")
        }
        var reencoded = false
        try {
            val copied = if (forceReencode) {
                extractor.release()
                false
            } else {
                copyPicture(app, source, extractor, videoTrack, out, durationUs, onProgress)
            }
            if (!copied) {
                reencoded = true
                reencode(app, source, out, onProgress)
            }
            resolver.update(out, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            onProgress(1f)
            // the real name the Gallery gave it (it adds " (1)" if the name was taken)
            val saved = MediaProbe.displayName(app, out).ifBlank { name }
            Saved(out, saved, reencoded)
        } catch (e: Throwable) {
            try {
                resolver.delete(out, null, null)
            } catch (_: Exception) {
            }
            when (e) {
                is Failed, is kotlinx.coroutines.CancellationException -> throw e
                else -> {
                    Log.w("SoundRemover", "removing the sound failed", e)
                    throw Failed("The sound could not be removed from this video.")
                }
            }
        }
    }

    /**
     * Copies the encoded picture into [out]. Returns false (nothing written) when the phone's MP4
     * writer does not take this kind of picture - then it is encoded again instead.
     */
    private suspend fun copyPicture(
        context: Context, source: Uri, extractor: MediaExtractor, track: Int, out: Uri, durationUs: Long,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val resolver = context.contentResolver
        val pfd = resolver.openFileDescriptor(out, "rwt") ?: throw Failed("The copy could not be written.")
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val m = try {
                MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { mm ->
                    mm.setOrientationHint(rotation(context, source, format))
                    location(context, source)?.let { (lat, lon) -> mm.setLocation(lat, lon) }
                }
            } catch (e: Exception) {
                return false
            }
            muxer = m
            val outTrack = try {
                m.addTrack(format).also {
                    m.start()
                    started = true
                }
            } catch (e: Exception) {
                Log.i("SoundRemover", "this picture cannot be copied (${format.getString(MediaFormat.KEY_MIME)}), encoding again", e)
                return false
            }
            var buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var lastReport = -1f
            var samples = 0
            while (true) {
                coroutineContext.ensureActive()
                val size = extractor.sampleSize
                if (size < 0 || extractor.sampleTrackIndex < 0) break
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw Failed("Protected videos cannot be copied.")
                if (size > StreamingMux.MAX_SAMPLE_BYTES) throw Failed("This video cannot be copied.")
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) break
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, read, extractor.sampleTime, flags)
                m.writeSampleData(outTrack, buffer, info)
                if (durationUs > 0) {
                    val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 0.99f)
                    if (p - lastReport >= 0.01f) {
                        lastReport = p
                        onProgress(p)
                    }
                }
                extractor.advance()
                samples++
                if (testDelayPerSampleMs > 0) kotlinx.coroutines.delay(testDelayPerSampleMs)
            }
            if (samples == 0) throw Failed("This video has no picture to copy.")
            m.stop()
            started = false
            return true
        } finally {
            if (started) try {
                muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            extractor.release()
            pfd.close()
        }
    }

    private fun rotation(context: Context, source: Uri, format: MediaFormat): Int {
        if (format.containsKey(MediaFormat.KEY_ROTATION)) return normal(format.getInteger(MediaFormat.KEY_ROTATION))
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, source)
            normal(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
        } catch (e: Exception) {
            0
        } finally {
            r.release()
        }
    }

    private fun normal(deg: Int) = ((deg % 360 + 360) % 360).let { if (it in listOf(0, 90, 180, 270)) it else 0 }

    /** The place the video was filmed ("+12.3456+078.9012/"), kept in the copy. */
    private fun location(context: Context, source: Uri): Pair<Float, Float>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, source)
            val s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) ?: return null
            val m = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(s) ?: return null
            val lat = m.groupValues[1].toFloat()
            val lon = m.groupValues[2].toFloat()
            if (lat in -90f..90f && lon in -180f..180f) lat to lon else null
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }

    /** The other way: the picture decoded and encoded again without sound (Transformer). */
    private suspend fun reencode(context: Context, source: Uri, out: Uri, onProgress: (Float) -> Unit) {
        val tmp = File(context.cacheDir, "jupiter_${System.nanoTime()}.mp4")
        try {
            withContext(Dispatchers.Main) {
                val transformer = Transformer.Builder(context).build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(source)).setRemoveAudio(true).build()
                val progress = androidx.media3.transformer.ProgressHolder()
                val handler = Handler(Looper.getMainLooper())
                val poll = object : Runnable {
                    override fun run() {
                        if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress((progress.progress / 100f * 0.95f).coerceIn(0f, 0.95f))
                        }
                        handler.postDelayed(this, 300)
                    }
                }
                try {
                    suspendCancellableCoroutine<Unit> { cont ->
                        transformer.addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        cont.invokeOnCancellation { handler.post { transformer.cancel() } }
                        transformer.start(item, tmp.absolutePath)
                        handler.post(poll)
                    }
                } finally {
                    handler.removeCallbacks(poll)
                }
            }
            val pfd = context.contentResolver.openOutputStream(out, "wt") ?: throw Failed("The copy could not be written.")
            pfd.use { o -> tmp.inputStream().use { it.copyTo(o, 256 * 1024) } }
        } finally {
            tmp.delete()
        }
    }
}
