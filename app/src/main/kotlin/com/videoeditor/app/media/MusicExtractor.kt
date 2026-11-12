package com.videoeditor.app.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.videoeditor.core.VocalRemovalStatus
import com.videoeditor.core.VocalRemover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class ExtractOutcome {
    object Saved : ExtractOutcome()
    /** Nothing was saved; [message] tells the user why. */
    data class Failed(val message: String) : ExtractOutcome()
}

/**
 * "Extract Music from Video": decodes the audio track of a video on-device, removes the voice
 * with [VocalRemover] and writes the remaining background music to [outFile] as a WAV file.
 * The video itself is only read. If the voice cannot be separated (mono sound, no sound) the
 * output file is deleted and a reason is returned.
 */
object MusicExtractor {
    suspend fun extract(context: Context, uri: Uri, outFile: File, onProgress: (Float) -> Unit): ExtractOutcome =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            var wav: WavWriter? = null
            var saved = false
            try {
                extractor.setDataSource(context, uri, null)
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
                    return@withContext ExtractOutcome.Failed("This video has no sound, so there is no music to extract.")
                }
                extractor.selectTrack(trackIndex)
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

                val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec = decoder
                decoder.configure(format, null, null, 0)
                decoder.start()

                var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var pcmFloat = false
                var remover: VocalRemover? = null
                var left = FloatArray(0)
                var right = FloatArray(0)
                val sink: (FloatArray, FloatArray, Int) -> Unit = { l, r, n -> wav?.write(l, r, n) }

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
                            if (size < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
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
                            if (frames > 0) {
                                if (channels < 2) {
                                    decoder.releaseOutputBuffer(outIndex, false)
                                    return@withContext ExtractOutcome.Failed(MONO_MESSAGE)
                                }
                                val out = decoder.getOutputBuffer(outIndex)!!
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                out.order(ByteOrder.nativeOrder())
                                if (left.size < frames) {
                                    left = FloatArray(frames)
                                    right = FloatArray(frames)
                                }
                                // channel 0 = front left, channel 1 = front right (also for surround sound)
                                if (pcmFloat) {
                                    val fb = out.asFloatBuffer()
                                    for (i in 0 until frames) {
                                        left[i] = fb.get(i * channels)
                                        right[i] = fb.get(i * channels + 1)
                                    }
                                } else {
                                    val sb = out.asShortBuffer()
                                    for (i in 0 until frames) {
                                        left[i] = sb.get(i * channels) / 32768f
                                        right[i] = sb.get(i * channels + 1) / 32768f
                                    }
                                }
                                val vr = remover ?: VocalRemover(sampleRate).also {
                                    remover = it
                                    wav = WavWriter(outFile, sampleRate)
                                }
                                vr.process(left, right, frames, sink)
                                if (durationUs > 0) {
                                    onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                                }
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }

                val status = remover?.finish(sink) ?: VocalRemovalStatus.EMPTY
                wav?.close()
                wav = null
                when (status) {
                    VocalRemovalStatus.OK -> {
                        saved = true
                        ExtractOutcome.Saved
                    }
                    VocalRemovalStatus.MONO_SOURCE -> ExtractOutcome.Failed(MONO_MESSAGE)
                    VocalRemovalStatus.NOTHING_LEFT ->
                        ExtractOutcome.Failed("After removing the voice no background music was left in this video. Nothing was saved.")
                    VocalRemovalStatus.EMPTY ->
                        ExtractOutcome.Failed("The sound of this video is empty or could not be read. Nothing was saved.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ExtractOutcome.Failed("The music could not be extracted (${e.javaClass.simpleName}). Nothing was saved.")
            } finally {
                try {
                    wav?.close()
                } catch (e: Exception) {
                    // ignore
                }
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
                if (!saved) outFile.delete()
            }
        }

    private const val MONO_MESSAGE =
        "This video's sound is mono (left and right are the same), so the voice cannot be separated from the music. Nothing was saved."
}

/** Minimal 16-bit stereo PCM WAV writer. */
private class WavWriter(file: File, private val sampleRate: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    private var dataBytes = 0L
    private var closed = false

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    private fun toPcm(v: Float): Short = (v * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()

    fun write(left: FloatArray, right: FloatArray, count: Int) {
        for (i in 0 until count) {
            if (buffer.remaining() < 4) flush()
            buffer.putShort(toPcm(left[i]))
            buffer.putShort(toPcm(right[i]))
        }
        dataBytes += count * 4L
    }

    private fun flush() {
        raf.write(buffer.array(), 0, buffer.position())
        buffer.clear()
    }

    fun close() {
        if (closed) return
        closed = true
        flush()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())                       // PCM
        header.putShort(2.toShort())                       // stereo
        header.putInt(sampleRate)
        header.putInt(sampleRate * 4)            // byte rate
        header.putShort(4.toShort())                       // block align
        header.putShort(16.toShort())                      // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(header.array())
        raf.close()
    }
}
