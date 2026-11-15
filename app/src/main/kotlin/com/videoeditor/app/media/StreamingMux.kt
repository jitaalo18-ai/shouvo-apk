package com.videoeditor.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/** Copies encoded video and AAC, one sample at a time. Never decodes or holds the full video. */
internal object StreamingMux {
    const val MAX_SAMPLE_BYTES = 32 * 1024 * 1024

    suspend fun copy(context: Context, video: Uri, audio: File, output: File, durationMs: Long,
                     progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val picture = MediaExtractor()
        val sound = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            picture.setDataSource(context, video, null)
            sound.setDataSource(audio.absolutePath)
            fun track(e: MediaExtractor, type: String): Int = (0 until e.trackCount).firstOrNull {
                e.getTrackFormat(it).getString("mime")?.startsWith(type) == true
            } ?: error("The $type track is missing")
            val vi = track(picture, "video/")
            val ai = track(sound, "audio/")
            picture.selectTrack(vi)
            sound.selectTrack(ai)
            val m = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(context, video)
                val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation in listOf(0, 90, 180, 270)) m.setOrientationHint(rotation)
            } finally { metadata.release() }
            val vt = m.addTrack(picture.getTrackFormat(vi))
            val at = m.addTrack(sound.getTrackFormat(ai))
            m.start()
            started = true
            var buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var samples = 0
            var lastReport = -1f
            while (picture.sampleTime >= 0 || sound.sampleTime >= 0) {
                coroutineContext.ensureActive()
                val v = picture.sampleTime
                val a = sound.sampleTime
                val takeVideo = v >= 0 && (a < 0 || v <= a)
                val src = if (takeVideo) picture else sound
                val sampleSize = src.sampleSize
                check(sampleSize in 1..MAX_SAMPLE_BYTES.toLong()) { "Unsupported video sample size: $sampleSize bytes" }
                check(src.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted media cannot be exported" }
                if (sampleSize > buffer.capacity()) buffer = ByteBuffer.allocateDirect(sampleSize.toInt())
                buffer.clear()
                val size = src.readSampleData(buffer, 0)
                check(size == sampleSize.toInt()) { "Could not read a complete media sample" }
                info.set(0, size, src.sampleTime,
                    if (src.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(if (takeVideo) vt else at, buffer, info)
                if (takeVideo) {
                    val p = (info.presentationTimeUs / (durationMs.coerceAtLeast(1) * 1000f)).coerceIn(0f, 1f)
                    if (p - lastReport >= 0.01f) { lastReport = p; progress(p) }
                }
                src.advance()
                if (++samples % 128 == 0) check(output.parentFile!!.usableSpace > 32L * 1024 * 1024) {
                    "Storage is almost full. Free space and retry."
                }
            }
            m.stop()
            started = false
            progress(1f)
        } finally {
            if (started) try { muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } finally { picture.release(); sound.release() }
        }
    }
}
