package com.videoeditor.app.captions

import android.content.Context
import com.videoeditor.core.Clip
import com.videoeditor.core.SpeechPiece
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Auto Captions: the project's sound -> recognised speech with timeline times. */
object AutoCaptioner {
    class Failed(message: String) : Exception(message)

    fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    /**
     * [mainPieces]: the main track as it plays (compounds already flattened). [onProgress] gets a
     * short status text and 0..1 for the whole job.
     */
    suspend fun recognise(
        context: Context,
        mainPieces: List<Clip>,
        language: CaptionLanguage,
        onProgress: (String, Float) -> Unit,
    ): List<SpeechPiece> {
        if (!WhisperNative.available) throw Failed("Speech recognition is not available on this phone.")
        val model = language.model
        val file = CaptionModels.file(context, model)
        if (!CaptionModels.isReady(context, model)) throw Failed("Download the speech model first.")

        val audio = SpeechAudio.timeline(context, mainPieces) { onProgress("Reading the sound…", it * 0.08f) }
        if (audio.isEmpty() || audio.all { abs(it) < 1e-4f }) return emptyList()

        return withContext(Dispatchers.Default) {
            onProgress("Loading the speech model…", 0.08f)
            val ctx = WhisperNative.load(file.absolutePath)
            if (ctx == 0L) throw Failed("The speech model could not be loaded. Delete it and download it again.")
            // stopping the coroutine stops the native work too
            val stop = coroutineContext[Job]?.invokeOnCompletion { if (it is CancellationException) WhisperNative.cancel() }
            try {
                onProgress("Listening… 0%", 0.1f)
                val vad = CaptionModels.vadFile(context).takeIf { CaptionModels.looksLikeVad(it) }?.absolutePath
                val lines = WhisperNative.transcribe(
                    ctx, audio, language.code, threads(), model.maxLen, model.beamSize, vad,
                ) { p -> onProgress("Listening… $p%", 0.1f + 0.9f * p / 100f) }
                coroutineContext[Job]?.let { if (it.isCancelled) throw CancellationException() }
                lines ?: throw Failed("Speech recognition stopped before it finished.")
                lines.mapNotNull(::parse)
            } finally {
                stop?.dispose()
                WhisperNative.free(ctx)
            }
        }
    }

    fun parse(line: String): SpeechPiece? {
        val parts = line.split('\t', limit = 3)
        if (parts.size < 3) return null
        val t0 = parts[0].toLongOrNull() ?: return null
        val t1 = parts[1].toLongOrNull() ?: return null
        // a broken letter (should not happen any more) is dropped rather than shown as a box
        return SpeechPiece(t0, t1, parts[2].replace("\uFFFD", ""))
    }
}
