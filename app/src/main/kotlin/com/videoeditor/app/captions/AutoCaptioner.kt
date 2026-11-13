package com.videoeditor.app.captions

import android.content.Context
import com.videoeditor.core.Clip
import com.videoeditor.core.Captions
import com.videoeditor.core.MsRange
import com.videoeditor.core.SpeechDetector
import com.videoeditor.core.SpeechPiece
import com.videoeditor.core.VadStatus
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
        // where people really speak (the AutoCut voice detector), to time the captions exactly
        val speech = withContext(Dispatchers.Default) { speechRanges(audio) }

        return withContext(Dispatchers.Default) {
            onProgress("Loading the speech model…", 0.08f)
            val ctx = WhisperNative.load(file.absolutePath)
            if (ctx == 0L) throw Failed("The speech model could not be loaded. Delete it and download it again.")
            // stopping the coroutine stops the native work too
            val stop = coroutineContext[Job]?.invokeOnCompletion { if (it is CancellationException) WhisperNative.cancel() }
            try {
                // short parts at pauses: the recogniser can only write so much per part (Bangla
                // needs many tokens), and silences are skipped
                val chunks = Captions.planChunks(audio.size * 1000L / SpeechAudio.RATE, speech, { from, to -> quietest(audio, from, to) })
                val total = chunks.sumOf { it.lengthMs }.coerceAtLeast(1)
                var done = 0L
                val pieces = ArrayList<SpeechPiece>()
                for (chunk in chunks) {
                    coroutineContext[Job]?.let { if (it.isCancelled) throw CancellationException() }
                    val from = (chunk.startMs * SpeechAudio.RATE / 1000).toInt().coerceIn(0, audio.size)
                    val to = (chunk.endMs * SpeechAudio.RATE / 1000).toInt().coerceIn(from, audio.size)
                    if (to - from < SpeechAudio.RATE / 5) continue
                    val doneBefore = done
                    val lines = WhisperNative.transcribe(
                        ctx, audio.copyOfRange(from, to), language.code, threads(), model.maxLen, model.beamSize, null,
                    ) { p ->
                        val whole = (doneBefore + chunk.lengthMs * p / 100f) / total
                        onProgress("Listening… ${(whole * 100).toInt()}%", 0.1f + 0.9f * whole)
                    }
                    coroutineContext[Job]?.let { if (it.isCancelled) throw CancellationException() }
                    lines ?: throw Failed("Speech recognition stopped before it finished.")
                    for (piece in lines.mapNotNull(::parse)) {
                        val start = (chunk.startMs + piece.startMs).coerceAtMost(chunk.endMs)
                        val end = (chunk.startMs + piece.endMs).coerceIn(start, chunk.endMs)
                        pieces += piece.copy(startMs = start, endMs = end)
                    }
                    done += chunk.lengthMs
                }
                if (speech.isEmpty()) pieces else Captions.alignToSpeech(pieces, speech)
            } finally {
                stop?.dispose()
                WhisperNative.free(ctx)
            }
        }
    }

    /** The quietest 100 ms between [fromMs] and [toMs] (a good place to cut). */
    fun quietest(audio: FloatArray, fromMs: Long, toMs: Long): Long {
        val step = SpeechAudio.RATE / 10
        var best = fromMs
        var bestEnergy = Double.MAX_VALUE
        var ms = fromMs
        while (ms + 100 <= toMs) {
            val i0 = (ms * SpeechAudio.RATE / 1000).toInt()
            if (i0 + step > audio.size) break
            var e = 0.0
            for (k in i0 until i0 + step) e += audio[k] * audio[k]
            if (e < bestEnergy) {
                bestEnergy = e
                best = ms + 50
            }
            ms += 50
        }
        return best
    }

    /** Speech ranges (ms) found by the voice detector, or empty when it is not sure. */
    fun speechRanges(audio: FloatArray): List<MsRange> {
        val detector = SpeechDetector(SpeechAudio.RATE)
        val chunk = ShortArray(16_000)
        var i = 0
        while (i < audio.size) {
            val n = minOf(chunk.size, audio.size - i)
            for (k in 0 until n) chunk[k] = (audio[i + k] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            detector.accept(chunk, n)
            i += n
        }
        val result = detector.finish()
        return if (result.status == VadStatus.OK) result.speech else emptyList()
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
