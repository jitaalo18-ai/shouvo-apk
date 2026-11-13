package com.videoeditor.app.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.videoeditor.core.AutoCutPlan
import com.videoeditor.core.AutoCutStrength
import com.videoeditor.core.MsRange
import com.videoeditor.core.AutoCutPlanner
import com.videoeditor.core.Clip
import com.videoeditor.core.MonoDownsampler
import com.videoeditor.core.SpeechDetector
import com.videoeditor.core.VadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

sealed class AutoCutOutcome {
    /**
     * Speech was found. [speech] is relative to [baseSourceMs] (source time of the first analysed
     * sample); plan it with [planFor] for any strength without analysing again.
     */
    data class Ready(val clip: Clip, val speech: List<MsRange>, val baseSourceMs: Long) : AutoCutOutcome() {
        fun planFor(strength: AutoCutStrength): AutoCutPlan =
            AutoCutPlanner.plan(speech, baseSourceMs, clip.sourceStartMs, clip.sourceEndMs, strength.config)
    }
    /** Nothing was changed; [message] tells the user why. */
    data class Unchanged(val message: String) : AutoCutOutcome()
}

/**
 * AutoCut Engine (Android side): decodes the ORIGINAL audio track of the clip's source range to
 * PCM on-device, runs the voice activity detector over it and returns the source ranges to keep.
 * It only reads the source file. The timeline change itself is applied by TimelineOps.
 */
object AutoCutEngine {
    suspend fun analyze(context: Context, clip: Clip, onProgress: (Float) -> Unit): AutoCutOutcome =
        withContext(Dispatchers.Default) {
            if (!clip.hasAudio) {
                return@withContext AutoCutOutcome.Unchanged("This clip has no audio, so nothing was removed.")
            }
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, Uri.parse(clip.sourceUri), null)
                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        trackIndex = i
                        format = f
                        break
                    }
                }
                if (trackIndex < 0 || format == null) {
                    return@withContext AutoCutOutcome.Unchanged("This clip has no audio, so nothing was removed.")
                }
                extractor.selectTrack(trackIndex)
                val startUs = clip.sourceStartMs * 1000
                val endUs = clip.sourceEndMs * 1000
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec = decoder
                decoder.configure(format, null, null, 0)
                decoder.start()

                var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var pcmFloat = false
                var downsampler: MonoDownsampler? = null
                var detector: SpeechDetector? = null
                var baseUs = -1L
                var floats = FloatArray(0)
                var shorts = ShortArray(0)

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    ensureActive()
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = decoder.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            val time = extractor.sampleTime
                            if (size < 0 || time > endUs) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, time, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = decoder.outputFormat
                            sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        outIndex >= 0 -> {
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            val bytesPerSample = if (pcmFloat) 4 else 2
                            val frames = info.size / (bytesPerSample * channels)
                            val bufferEndUs = info.presentationTimeUs + frames * 1_000_000L / sampleRate
                            if (frames > 0 && bufferEndUs > startUs && info.presentationTimeUs < endUs) {
                                val out = decoder.getOutputBuffer(outIndex)!!
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                out.order(ByteOrder.nativeOrder())
                                if (floats.size < frames * channels) floats = FloatArray(frames * channels)
                                if (pcmFloat) {
                                    out.asFloatBuffer().get(floats, 0, frames * channels)
                                } else {
                                    val sb = out.asShortBuffer()
                                    for (i in 0 until frames * channels) floats[i] = sb.get(i) / 32768f
                                }
                                val ds = downsampler ?: MonoDownsampler(sampleRate, channels).also {
                                    downsampler = it
                                    detector = SpeechDetector(it.effectiveRate)
                                    baseUs = info.presentationTimeUs
                                }
                                val need = ds.maxOut(frames)
                                if (shorts.size < need) shorts = ShortArray(need)
                                val n = ds.process(floats, frames, shorts)
                                detector?.accept(shorts, n)
                                val span = (endUs - startUs).coerceAtLeast(1)
                                onProgress(((info.presentationTimeUs - startUs).toFloat() / span).coerceIn(0f, 1f))
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }

                val result = detector?.finish()
                    ?: return@withContext AutoCutOutcome.Unchanged("The audio of this clip could not be read, so nothing was removed.")
                when (result.status) {
                    VadStatus.NO_AUDIO ->
                        AutoCutOutcome.Unchanged("The audio of this clip could not be read, so nothing was removed.")
                    VadStatus.NO_SPEECH ->
                        AutoCutOutcome.Unchanged("No speech was detected in this clip, so nothing was removed.")
                    VadStatus.UNRELIABLE ->
                        AutoCutOutcome.Unchanged("Speech could not be separated from the background reliably, so nothing was removed.")
                    VadStatus.OK -> {
                        val ready = AutoCutOutcome.Ready(clip, result.speech, baseUs / 1000)
                        val tight = ready.planFor(AutoCutStrength.TIGHT)
                        if (tight.keep.isEmpty() || tight.removedMs < 100) {
                            AutoCutOutcome.Unchanged("No pauses long enough to remove were found. Nothing was changed.")
                        } else {
                            ready
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AutoCutOutcome.Unchanged("AutoCut could not analyse this clip (${e.javaClass.simpleName}). Nothing was changed.")
            } finally {
                try {
                    codec?.stop()
                } catch (e: Exception) {
                    // ignore
                }
                try {
                    codec?.release()
                } catch (e: Exception) {
                    // ignore
                }
                extractor.release()
            }
        }
}
