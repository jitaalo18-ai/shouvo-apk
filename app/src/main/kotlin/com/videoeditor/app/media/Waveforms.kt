package com.videoeditor.app.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Loudness outline of an audio track for drawing waveforms on the timeline: one value (0..255)
 * per [BUCKET_MS] of the source. Computed once per file in the background by decoding its audio,
 * then cached in memory and in the app's cache folder. The source file is only read.
 */
object Waveforms {
    const val BUCKET_MS = 50L
    private val memory = HashMap<String, ByteArray>()
    private val worker = Dispatchers.IO.limitedParallelism(1)

    suspend fun load(context: Context, uri: String): ByteArray? {
        synchronized(memory) { memory[uri] }?.let { return it }
        return withContext(worker) {
            synchronized(memory) { memory[uri] }?.let { return@withContext it }
            val dir = File(context.cacheDir, "waveforms").apply { mkdirs() }
            val file = File(dir, "${uri.hashCode().toUInt()}_${uri.length}.bin")
            val peaks = try {
                if (file.exists() && file.length() > 0) file.readBytes() else compute(context, uri)?.also { file.writeBytes(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (peaks != null) synchronized(memory) { memory[uri] = peaks }
            peaks
        }
    }

    private suspend fun compute(context: Context, uri: String): ByteArray? = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
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
            if (trackIndex < 0 || format == null) return@withContext null
            extractor.selectTrack(trackIndex)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            var peaks = FloatArray(((durationMs / BUCKET_MS).toInt() + 16).coerceAtLeast(64))
            var used = 0

            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
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
                        val samples = info.size / (if (pcmFloat) 4 else 2)
                        if (samples > 0 && channels > 0 && sampleRate > 0) {
                            val out = decoder.getOutputBuffer(outIndex)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            out.order(ByteOrder.nativeOrder())
                            val startMs = info.presentationTimeUs / 1000
                            val fb = if (pcmFloat) out.asFloatBuffer() else null
                            val sb = if (pcmFloat) null else out.asShortBuffer()
                            // every 4th sample is plenty for an outline
                            var i = 0
                            while (i < samples) {
                                val v = if (fb != null) abs(fb.get(i)) else abs(sb!!.get(i) / 32768f)
                                val ms = startMs + (i / channels) * 1000L / sampleRate
                                val bucket = (ms / BUCKET_MS).toInt()
                                if (bucket >= 0) {
                                    if (bucket >= peaks.size) peaks = peaks.copyOf(maxOf(bucket + 1, peaks.size * 2))
                                    if (v > peaks[bucket]) peaks[bucket] = v
                                    if (bucket + 1 > used) used = bucket + 1
                                }
                                i += 4
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
            if (used == 0) return@withContext null
            // square root makes quiet speech visible next to loud parts
            ByteArray(used) { (sqrt(peaks[it].coerceIn(0f, 1f)) * 255f).toInt().toByte() }
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
