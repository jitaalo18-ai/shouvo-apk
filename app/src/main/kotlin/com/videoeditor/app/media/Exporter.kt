package com.videoeditor.app.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.videoeditor.core.Clip
import com.videoeditor.core.MediaKind
import com.videoeditor.core.Timeline
import com.videoeditor.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Output frame size for a project: short side 1080, long side at most 1920, even numbers. */
fun canvasSize(aspect: Float): Pair<Int, Int> {
    val a = if (aspect.isFinite() && aspect > 0f) aspect else 16f / 9f
    var w: Float
    var h: Float
    if (a >= 1f) {
        h = 1080f
        w = h * a
    } else {
        w = 1080f
        h = w / a
    }
    val long = maxOf(w, h)
    if (long > 1920f) {
        val k = 1920f / long
        w *= k
        h *= k
    }
    fun even(v: Float) = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
    return even(w) to even(h)
}

/**
 * Media Rendering Engine: renders the timeline to an MP4 with Media3 Transformer and saves it to
 * Movies/VideoEditor. Sources are only read; the edit decisions are applied while rendering.
 */
class Exporter(private val context: Context) {

    suspend fun export(timeline: Timeline, onProgress: (Float) -> Unit): Uri = withContext(Dispatchers.Main) {
        val main = timeline.main
        require(main.isNotEmpty()) { "Nothing to export" }
        val (canvasW, canvasH) = canvasSize(main.first().aspect)
        val overlays = timeline.track(Track.OVERLAY).sortedBy { it.layer }
            .map { TimedOverlay(context, it, canvasW, canvasH) }
        val composition = buildComposition(timeline, canvasW, canvasH, overlays)
        val outFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val transformer = Transformer.Builder(context).build()
        val progressJob = launch {
            val holder = ProgressHolder()
            while (isActive) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100f)
                }
                delay(300)
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { transformer.cancel() }
                }
                transformer.start(composition, outFile.absolutePath)
            }
            progressJob.cancel()
            onProgress(1f)
            withContext(Dispatchers.IO) { saveToMovies(outFile) }
        } finally {
            progressJob.cancel()
            outFile.delete()
        }
    }

    private fun buildComposition(
        timeline: Timeline,
        canvasW: Int,
        canvasH: Int,
        overlays: List<TextureOverlay>,
    ): Composition {
        val main = timeline.main
        val durationMs = timeline.durationMs
        val noAudio = emptyList<AudioProcessor>()

        val mainSequence = EditedMediaItemSequence.Builder()
        for (c in main) {
            val fit: Effect = Presentation.createForWidthAndHeight(canvasW, canvasH, Presentation.LAYOUT_SCALE_TO_FIT)
            val item = EditedMediaItem.Builder(mediaItem(c, c.sourceStartMs, c.sourceEndMs))
                .setEffects(Effects(noAudio, listOf(fit)))
            if (c.kind == MediaKind.IMAGE) {
                item.setDurationUs(c.durationMs * 1000).setFrameRate(30)
            }
            mainSequence.addItem(item.build())
        }
        val sequences = ArrayList<EditedMediaItemSequence>()
        sequences.add(mainSequence.build())

        // Sound effects, music and the audio of video overlays: one audio-only sequence each,
        // with a silent gap in front so it starts at its timeline position.
        val audioClips = timeline.clips.filter {
            it.track != Track.MAIN && it.kind != MediaKind.IMAGE && it.hasAudio && it.timelineStartMs < durationMs
        }
        for (c in audioClips) {
            val end = minOf(c.sourceEndMs, c.sourceStartMs + (durationMs - c.timelineStartMs))
            if (end - c.sourceStartMs < 50) continue
            val item = EditedMediaItem.Builder(mediaItem(c, c.sourceStartMs, end)).setRemoveVideo(true).build()
            val seq = EditedMediaItemSequence.Builder()
            if (c.timelineStartMs > 0) seq.addGap(c.timelineStartMs * 1000)
            seq.addItem(item)
            sequences.add(seq.build())
        }

        val builder = Composition.Builder(sequences)
        if (overlays.isNotEmpty()) {
            builder.setEffects(Effects(noAudio, listOf(OverlayEffect(ImmutableList.copyOf(overlays)))))
        }
        // A main track that starts with a silent item but has sound later needs a silent audio
        // track generated from the start.
        if (main.any { it.hasAudio } && !main.first().hasAudio) {
            builder.experimentalSetForceAudioTrack(true)
        }
        return builder.build()
    }

    private fun mediaItem(c: Clip, startMs: Long, endMs: Long): MediaItem {
        val b = MediaItem.Builder().setUri(c.sourceUri)
        if (c.kind == MediaKind.IMAGE) {
            b.setMimeType(c.mimeType ?: "image/jpeg").setImageDurationMs(endMs - startMs)
        } else {
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build(),
            )
        }
        return b.build()
    }

    private fun saveToMovies(file: File): Uri {
        val resolver = context.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VideoEditor_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoEditor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("Could not create the output file")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}

/**
 * One overlay clip drawn on top of the composited main video while rendering. Visible only
 * between the clip's timeline start and end; positioned and sized like in the preview.
 * Video overlays pull the matching source frame for every output frame.
 */
private class TimedOverlay(
    private val context: Context,
    private val clip: Clip,
    canvasW: Int,
    canvasH: Int,
) : BitmapOverlay() {
    private val targetW = (clip.scale * canvasW).roundToInt().coerceAtLeast(2)
    private val targetH = (targetW / clip.aspect.coerceAtLeast(0.01f)).roundToInt().coerceAtLeast(2)
    private val visible: OverlaySettings = OverlaySettings.Builder()
        .setBackgroundFrameAnchor(clip.posX * 2f - 1f, 1f - clip.posY * 2f)
        .build()
    private val hidden: OverlaySettings = OverlaySettings.Builder().setAlphaScale(0f).build()
    private var bitmap: Bitmap? = null
    private var lastFrame = -1L
    private var retriever: MediaMetadataRetriever? = null

    private fun active(timeMs: Long) = timeMs >= clip.timelineStartMs && timeMs < clip.timelineEndMs

    private fun fitted(src: Bitmap): Bitmap =
        if (src.width == targetW && src.height == targetH) src
        else Bitmap.createScaledBitmap(src, targetW, targetH, true)

    private fun placeholder(): Bitmap =
        Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeMs = presentationTimeUs / 1000
        if (clip.kind == MediaKind.IMAGE) {
            bitmap?.let { return it }
            val loaded = try {
                Thumbnails.decodeImage(context, Uri.parse(clip.sourceUri), maxOf(targetW, targetH))
            } catch (e: Exception) {
                null
            }
            return (loaded?.let { fitted(it) } ?: placeholder()).also { bitmap = it }
        }
        if (!active(timeMs)) return bitmap ?: placeholder().also { bitmap = it }
        val sourceMs = clip.sourceStartMs + (timeMs - clip.timelineStartMs)
        val frame = sourceMs / 33
        if (frame != lastFrame || bitmap == null) {
            lastFrame = frame
            val grabbed = try {
                val r = retriever ?: MediaMetadataRetriever().also {
                    it.setDataSource(context, Uri.parse(clip.sourceUri))
                    retriever = it
                }
                r.getScaledFrameAtTime(sourceMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, targetW, targetH)
            } catch (e: Exception) {
                null
            }
            if (grabbed != null) bitmap = fitted(grabbed)
        }
        return bitmap ?: placeholder().also { bitmap = it }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        if (active(presentationTimeUs / 1000)) visible else hidden

    override fun release() {
        try {
            retriever?.release()
        } catch (e: Exception) {
            // ignore
        }
        retriever = null
        super.release()
    }
}
