package com.videoeditor.app.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Isolate voice: DeepFilterNet3 (MIT / Apache-2.0, a neural network that keeps speech and
 * removes everything else) through JNI. See app/src/main/cpp/isolate_jni.cpp and
 * third_party/deepfilternet.
 */
object VoiceIsolationNative {
    /** True when the library and the network are in this build. */
    val loaded: Boolean = try {
        System.loadLibrary("nockwhisper")
        available()
    } catch (e: Throwable) {
        false
    }

    external fun available(): Boolean
    /** Starts the network; [attenLimDb] 100 = remove everything that is not voice. 0 on failure. */
    external fun create(modelPath: String, attenLimDb: Float): Long
    external fun frameLength(handle: Long): Int
    /** [frames] whole frames of [frameLength] samples (48 kHz mono) in, the same out. */
    external fun process(handle: Long, input: FloatArray, output: FloatArray, frames: Int): Boolean
    external fun free(handle: Long)

    const val MODEL_ASSET = "df/DeepFilterNet3_onnx.tar.gz"
    private const val MODEL_SIZE = 7_983_136L
    private const val MODEL_SHA256 = "c94d91f70911001c946e0fabb4aa9adc37045f45a03b56008cb0c8244cb63616"

    /** Delay of DeepFilterNet3 in samples: STFT (960 - 480) + 2 frames of look-ahead (480 each). */
    const val DELAY = 1_440

    @Volatile
    private var checkedModel: File? = null

    /**
     * The network file in app storage (copied out of the APK once and checked byte for byte: a
     * damaged file would make the native code stop the app).
     */
    @Synchronized
    fun model(context: Context): File {
        checkedModel?.let { if (it.length() == MODEL_SIZE) return it }
        val f = File(context.filesDir, MODEL_ASSET)
        if (!(f.length() == MODEL_SIZE && sha256(f) == MODEL_SHA256)) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            context.assets.open(MODEL_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.length() == MODEL_SIZE && sha256(tmp) == MODEL_SHA256) { "voice network file damaged" }
            f.delete()
            check(tmp.renameTo(f)) { "cannot store the voice network" }
        }
        checkedModel = f
        return f
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Runs 48 kHz mono sound through the network and writes the voice to a 16-bit WAV file whose
 * sample 0 is time 0 of the source file (the network's delay is taken out, the time before the
 * first sound is silence), as long as the whole file (video included), so the voice stays in
 * step with the picture.
 */
class IsolationWriter(model: File, val target: File) {
    private val handle = VoiceIsolationNative.create(model.absolutePath, 100f)
    private val hop: Int
    private val raf: RandomAccessFile
    private var pending = FloatArray(0)
    private var pendingCount = 0
    private var skip = VoiceIsolationNative.DELAY.toLong()
    private var dropInput = 0L
    /** Sound samples given (silence in front included). */
    private var inputSamples = 0L
    private var written = 0L
    private val batch: FloatArray
    private val result: FloatArray
    private val bytes: ByteBuffer

    init {
        check(handle != 0L) { "voice network did not start" }
        hop = VoiceIsolationNative.frameLength(handle)
        check(hop > 0)
        batch = FloatArray(hop * BATCH_FRAMES)
        result = FloatArray(hop * BATCH_FRAMES)
        bytes = ByteBuffer.allocate(hop * BATCH_FRAMES * 2).order(ByteOrder.LITTLE_ENDIAN)
        target.parentFile?.mkdirs()
        raf = RandomAccessFile(target, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44))                // header written at the end
    }

    /** The first sound is at [firstPtsUs] in the source: silence before it (or a cut, if before 0). */
    fun start(firstPtsUs: Long) {
        val lead = Math.round(firstPtsUs * RATE / 1_000_000.0)
        if (lead > 0) {
            val zeros = FloatArray(4_800)
            var left = lead
            while (left > 0) {
                val n = minOf(left, zeros.size.toLong()).toInt()
                write(zeros, n)
                left -= n
            }
        } else if (lead < 0) {
            dropInput = -lead
        }
    }

    fun write(samples: FloatArray, count: Int = samples.size) {
        var from = 0
        var n = count
        if (dropInput > 0) {
            val d = minOf(dropInput, n.toLong()).toInt()
            dropInput -= d
            from += d
            n -= d
        }
        if (n <= 0) return
        inputSamples += n
        if (pending.size < pendingCount + n) pending = pending.copyOf(maxOf(pending.size * 2, pendingCount + n, batch.size))
        System.arraycopy(samples, from, pending, pendingCount, n)
        pendingCount += n
        run(final = false)
    }

    private fun run(final: Boolean) {
        var at = 0
        while (pendingCount - at >= hop || (final && pendingCount - at > 0)) {
            val avail = pendingCount - at
            val frames = if (avail >= batch.size) BATCH_FRAMES else maxOf(1, if (final) (avail + hop - 1) / hop else avail / hop)
            val take = minOf(avail, frames * hop)
            System.arraycopy(pending, at, batch, 0, take)
            java.util.Arrays.fill(batch, take, frames * hop, 0f)
            check(VoiceIsolationNative.process(handle, batch, result, frames)) { "voice network failed" }
            emit(result, frames * hop)
            at += take
        }
        if (at > 0) {
            System.arraycopy(pending, at, pending, 0, pendingCount - at)
            pendingCount -= at
        }
    }

    private fun emit(out: FloatArray, count: Int) {
        var from = 0
        if (skip > 0) {
            val s = minOf(skip, count.toLong()).toInt()
            skip -= s
            from = s
        }
        // never more than was put in (the tail of the last frame is padding)
        val n = minOf((count - from).toLong(), inputSamples - written).toInt()
        if (n <= 0) return
        bytes.clear()
        for (i in from until from + n) {
            val v = (out[i] * 32767f).toInt().coerceIn(-32768, 32767)
            bytes.putShort(v.toShort())
        }
        raf.write(bytes.array(), 0, n * 2)
        written += n
    }

    /** Pushes the last sound through (the network's delay), pads to [durationUs], writes the header. */
    fun finish(durationUs: Long) {
        run(final = true)
        // the delay: feed silence until every input sample has come out
        val zeros = FloatArray(hop)
        var guard = 0
        while (written < inputSamples && guard++ < 100) {
            check(VoiceIsolationNative.process(handle, zeros, result, 1))
            emit(result, hop)
        }
        // as long as the file: silence after the sound
        val total = maxOf(written, Math.round(durationUs * RATE / 1_000_000.0))
        val pad = ByteArray(9_600)
        while (written < total) {
            val n = minOf(total - written, (pad.size / 2).toLong()).toInt()
            raf.write(pad, 0, n * 2)
            written += n
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + written * 2).toInt()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt((written * 2).toInt())
        raf.seek(0)
        raf.write(header.array())
        raf.fd.sync()
    }

    fun abandon() {
        close()
        target.delete()
    }

    private var closed = false

    /** Closes the file and frees the network (once, however often it is called). */
    fun close() {
        if (closed) return
        closed = true
        try {
            raf.close()
        } catch (e: Exception) {
        }
        VoiceIsolationNative.free(handle)
    }

    companion object {
        const val RATE = 48_000
        private const val BATCH_FRAMES = 100
    }
}

/** A playlist item whose sound comes from the isolated voice file instead of its own track. */
data class IsolatedAudio(val file: String)

/**
 * Builds the player's / exporter's sources: an item tagged [IsolatedAudio] plays its own video
 * (its own sound left out) together with the isolated voice, both cut to the same part of the
 * source, so the voice is exactly in step with the picture.
 */
class VoiceMediaSourceFactory(context: Context, forExport: Boolean = false) : MediaSource.Factory {
    // the export reads Samsung slow-motion data like Transformer's own loader does
    private val default = if (forExport) {
        DefaultMediaSourceFactory(context, DefaultExtractorsFactory().setMp4ExtractorFlags(Mp4Extractor.FLAG_READ_SEF_DATA))
    } else DefaultMediaSourceFactory(context)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        default.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        default.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = default.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val tag = mediaItem.localConfiguration?.tag as? IsolatedAudio ?: return default.createMediaSource(mediaItem)
        val picture = FilteringMediaSource(default.createMediaSource(mediaItem), setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_IMAGE))
        val voice = default.createMediaSource(
            MediaItem.Builder()
                .setUri(Uri.fromFile(File(tag.file)))
                .setMimeType("audio/wav")
                .setClippingConfiguration(mediaItem.clippingConfiguration)
                .build(),
        )
        return MergingMediaSource(/* adjustPeriodTimeOffsets= */ false, /* clipDurations= */ true, picture, voice)
    }
}
