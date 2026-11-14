package com.videoeditor.app.captions

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.videoeditor.core.Clip
import com.videoeditor.core.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * The sound of the main track exactly as it plays (pieces in timeline order, images and silent
 * clips as silence), as 16 kHz mono floats - what the speech recogniser needs. Sample i is at
 * timeline time i / 16 ms, so recognised times are timeline times.
 */
object SpeechAudio {
    const val RATE = 16_000

    suspend fun timeline(context: Context, mainPieces: List<Clip>, onProgress: (Float) -> Unit): FloatArray =
        withContext(Dispatchers.Default) {
            val totalMs = mainPieces.maxOfOrNull { it.timelineEndMs } ?: 0L
            val out = FloatArray((totalMs * RATE / 1000).toInt())
            val audible = mainPieces.filter { it.hasAudio && (it.kind == MediaKind.VIDEO || it.kind == MediaKind.AUDIO) }
            val sum = audible.sumOf { it.durationMs }.coerceAtLeast(1)
            var done = 0L
            for (c in audible) {
                ensureActive()
                val offset = (c.timelineStartMs * RATE / 1000).toInt()
                val maxSamples = (c.durationMs * RATE / 1000).toInt()
                try {
                    decodeInto(context, Uri.parse(c.sourceUri), c.sourceStartMs, c.sourceEndMs, out, offset, maxSamples) { f ->
                        onProgress(((done + f * c.durationMs) / sum).coerceIn(0f, 1f))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // unreadable sound: that part stays silent
                }
                done += c.durationMs
            }
            onProgress(1f)
            out
        }

    /** The sound of one clip ([Clip.sourceStartMs]..[Clip.sourceEndMs]) as 16 kHz mono (for analysis). */
    suspend fun clip(context: Context, c: Clip): FloatArray = withContext(Dispatchers.Default) {
        val out = FloatArray((c.durationMs * RATE / 1000).toInt())
        try {
            decodeInto(context, Uri.parse(c.sourceUri), c.sourceStartMs, c.sourceEndMs, out, 0, out.size) {}
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // unreadable: silent
        }
        out
    }

    /**
     * Decodes [startMs]..[endMs] of the first audio track of [uri], mixes it to mono, resamples
     * it to 16 kHz (averaging, which also filters out what is too high to keep) and writes it
     * into [out] from [offset], at most [maxSamples] samples.
     */
    private suspend fun decodeInto(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        out: FloatArray,
        offset: Int,
        maxSamples: Int,
        onProgress: (Float) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val startUs = startMs * 1000
            val endUs = endMs * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            var floats = FloatArray(0)
            // resampler state: running sum over the input samples of the current output sample
            var acc = 0.0
            var accN = 0
            var written = 0
            var inPos = 0.0          // position of the next input sample, in output samples
            var started = false

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && written < maxSamples) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = decoder.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buf, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > endUs) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = decoder.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    val bytes = if (pcmFloat) 4 else 2
                    val frames = info.size / (bytes * channels)
                    if (frames > 0) {
                        val buf = decoder.getOutputBuffer(o)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        if (floats.size < frames * channels) floats = FloatArray(frames * channels)
                        if (pcmFloat) buf.asFloatBuffer().get(floats, 0, frames * channels)
                        else {
                            val sb = buf.asShortBuffer()
                            for (k in 0 until frames * channels) floats[k] = sb.get(k) / 32768f
                        }
                        val step = RATE.toDouble() / rate
                        for (f in 0 until frames) {
                            val tUs = info.presentationTimeUs + f * 1_000_000L / rate
                            if (tUs < startUs) continue
                            if (tUs >= endUs) break
                            if (!started) {
                                started = true
                                inPos = 0.0
                            }
                            var mono = 0f
                            for (ch in 0 until channels) mono += floats[f * channels + ch]
                            acc += mono / channels
                            accN++
                            inPos += step
                            if (inPos >= 1.0) {
                                // one output sample per 1/16000 s (repeated when the source is slower)
                                val value = (acc / accN).toFloat()
                                while (inPos >= 1.0) {
                                    inPos -= 1.0
                                    if (written < maxSamples && offset + written < out.size) out[offset + written] = value
                                    written++
                                }
                                acc = 0.0
                                accN = 0
                            }
                        }
                        onProgress(((info.presentationTimeUs - startUs).toFloat() / (endUs - startUs).coerceAtLeast(1)).coerceIn(0f, 1f))
                    }
                    decoder.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
            }
            try {
                codec?.release()
            } catch (e: Exception) {
            }
            extractor.release()
        }
    }
}
