package com.videoeditor.app.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.videoeditor.core.AudioFx
import com.videoeditor.core.FxSegment
import com.videoeditor.core.PlayMap
import com.videoeditor.core.VoiceFx
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** 16-bit / float PCM <-> interleaved floats. */
internal object Pcm {
    fun supported(encoding: Int) = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT

    fun bytes(encoding: Int) = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2

    /** Reads [samples] samples from [src] (from its position, which is not moved). */
    fun read(src: ByteBuffer, encoding: Int, samples: Int, dst: FloatArray) {
        val b = src.duplicate().order(ByteOrder.nativeOrder())
        if (encoding == C.ENCODING_PCM_FLOAT) {
            b.asFloatBuffer().get(dst, 0, samples)
        } else {
            val s = b.asShortBuffer()
            for (i in 0 until samples) dst[i] = s.get(i) / 32768f
        }
    }

    fun write(src: FloatArray, from: Int, samples: Int, encoding: Int, dst: ByteBuffer) {
        if (encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until samples) dst.putFloat(src[from + i])
        } else {
            for (i in 0 until samples) {
                val v = (src[from + i] * 32768f).toInt().coerceIn(-32768, 32767)
                dst.putShort(v.toShort())
            }
        }
    }
}

/**
 * Export: applies one clip's Voice enhance settings to its sound. Positions count from the start
 * of the clip; fades end at [durationUs]. Inactive when the settings change nothing.
 */
class VoiceFxProcessor(private val fx: AudioFx, private val durationUs: Long) : BaseAudioProcessor() {
    private var engine: VoiceFx? = null
    private var frames = 0L
    private var floats = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (fx.isDefault || !Pcm.supported(inputAudioFormat.encoding)) return AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        val f = inputAudioFormat
        engine = if (f == AudioFormat.NOT_SET) null else VoiceFx(f.sampleRate, f.channelCount, compensate = true).apply {
            plan = listOf(FxSegment("clip", Long.MIN_VALUE / 4, Long.MAX_VALUE / 4, fx, 0, durationUs))
        }
        frames = 0
    }

    override fun onReset() {
        engine = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val e = engine ?: return
        val enc = inputAudioFormat.encoding
        val ch = inputAudioFormat.channelCount
        val samples = inputBuffer.remaining() / Pcm.bytes(enc)
        val count = samples / ch
        if (count == 0) return
        if (floats.size < samples) floats = FloatArray(samples)
        Pcm.read(inputBuffer, enc, count * ch, floats)
        inputBuffer.position(inputBuffer.position() + count * ch * Pcm.bytes(enc))
        val out = e.process(floats, count, frames * 1_000_000L / inputAudioFormat.sampleRate)
        frames += count
        emit(out)
    }

    override fun onQueueEndOfStream() {
        val e = engine ?: return
        emit(e.drain())
    }

    private fun emit(out: FloatArray) {
        if (out.isEmpty()) return
        val enc = inputAudioFormat.encoding
        val buf = replaceOutputBuffer(out.size * Pcm.bytes(enc))
        Pcm.write(out, 0, out.size, enc, buf)
        buf.flip()
    }
}

/**
 * What one preview player's sound processing needs: the settings over time and, for the main
 * player, how to tell which clip a piece of sound belongs to. Written on the main thread, read
 * on the playback thread.
 */
class FxState(
    /** True for a player that plays one source file: stream time is the source time. */
    val sourceTime: Boolean,
) {
    val map = PlayMap()

    @Volatile
    var plan: List<FxSegment> = emptyList()
        set(value) {
            field = value
            active = value.any { !it.fx.isDefault }
        }

    @Volatile
    var active = false
        private set

    // for tests: how much sound was processed for each clip, and where the first sound after a jump was
    val framesByKey = ConcurrentHashMap<String, Long>()
    /** Sum of squared samples going in / coming out, by clip (the output lags by ~20 ms). */
    val powerIn = ConcurrentHashMap<String, Double>()
    val powerOut = ConcurrentHashMap<String, Double>()

    @Volatile
    var firstAfterFlushUs: Long = -1

    @Volatile
    var flushes = 0
}

/**
 * Preview: runs the decoded sound of a player through [VoiceFx] before it goes to the speaker.
 * The output is as long as the input (a constant delay of about 20 ms), so the player's clock is
 * unaffected.
 */
class VoiceFxSink(sink: AudioSink, private val state: FxState) : ForwardingAudioSink(sink) {
    private var engine: VoiceFx? = null
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var rate = 0
    private var offsetUs = 0L
    private var pending: ByteBuffer? = null
    private var outBuffer: ByteBuffer? = null
    private var floats = FloatArray(0)
    private var fifo = FloatArray(0)
    private var fifoCount = 0
    private var processing = false
    private var firstAfterFlush = true

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val pcm = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType && Pcm.supported(inputFormat.pcmEncoding)
        if (!pcm) {
            engine = null
        } else if (engine == null || inputFormat.pcmEncoding != encoding || inputFormat.channelCount != channels ||
            inputFormat.sampleRate != rate
        ) {
            encoding = inputFormat.pcmEncoding
            channels = inputFormat.channelCount
            rate = inputFormat.sampleRate
            engine = VoiceFx(rate, channels, compensate = false)
            restart()
        }
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        offsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        var out = pending
        if (out == null) {
            val e = engine
            if (e == null || !state.active || buffer.remaining() == 0) {
                processing = false
                return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
            if (!processing) {
                processing = true
                restart()
            }
            val samples = buffer.remaining() / Pcm.bytes(encoding)
            val frames = samples / channels
            val posUs = if (state.sourceTime) presentationTimeUs - offsetUs else state.map.locate(presentationTimeUs, offsetUs)
            if (firstAfterFlush) {
                firstAfterFlush = false
                state.firstAfterFlushUs = posUs
            }
            if (floats.size < frames * channels) floats = FloatArray(frames * channels)
            Pcm.read(buffer, encoding, frames * channels, floats)
            e.plan = state.plan
            push(e.process(floats, frames, posUs))
            val bytes = samples * Pcm.bytes(encoding)
            var o = outBuffer
            if (o == null || o.capacity() < bytes) {
                o = ByteBuffer.allocateDirect(bytes * 2).order(ByteOrder.nativeOrder())
                outBuffer = o
            }
            o!!.clear()
            out = o
            val take = minOf(fifoCount, frames * channels)
            count(posUs, frames, floats, fifo, take)
            Pcm.write(fifo, 0, take, encoding, out)
            // (never short: the delay line is primed, but stay safe)
            for (i in take until samples) Pcm.write(ZERO, 0, 1, encoding, out)
            System.arraycopy(fifo, take, fifo, 0, fifoCount - take)
            fifoCount -= take
            out.flip()
            pending = out
        }
        val done = super.handleBuffer(out, presentationTimeUs, encodedAccessUnitCount)
        if (done) {
            pending = null
            buffer.position(buffer.limit())
        }
        return done
    }

    override fun flush() {
        pending = null
        restart()
        state.map.forget()
        state.flushes++
        firstAfterFlush = true
        super.flush()
    }

    override fun reset() {
        pending = null
        restart()
        state.map.forget()
        firstAfterFlush = true
        super.reset()
    }

    /** Empties the sound in flight and primes the delay line (so output never runs short). */
    private fun restart() {
        val e = engine ?: return
        e.reset()
        val hop = e.latency
        fifoCount = hop * channels
        if (fifo.size < fifoCount * 4) fifo = FloatArray(fifoCount * 4)
        java.util.Arrays.fill(fifo, 0, fifoCount, 0f)
    }

    private fun push(samples: FloatArray) {
        if (fifoCount + samples.size > fifo.size) fifo = fifo.copyOf((fifoCount + samples.size) * 2)
        System.arraycopy(samples, 0, fifo, fifoCount, samples.size)
        fifoCount += samples.size
    }

    private fun count(posUs: Long, frames: Int, input: FloatArray, output: FloatArray, outSamples: Int) {
        val seg = state.plan.firstOrNull { posUs >= it.startUs && posUs < it.endUs } ?: return
        state.framesByKey.merge(seg.key, frames.toLong()) { x, y -> x + y }
        var pin = 0.0
        for (i in 0 until frames * channels) pin += input[i] * input[i]
        var pout = 0.0
        for (i in 0 until outSamples) pout += output[i] * output[i]
        state.powerIn.merge(seg.key, pin) { x, y -> x + y }
        state.powerOut.merge(seg.key, pout) { x, y -> x + y }
    }

    private companion object {
        val ZERO = FloatArray(1)
    }
}

/** Player renderers whose sound goes through a [VoiceFxSink]. */
class FxRenderersFactory(context: Context, private val state: FxState) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
        super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)?.let { VoiceFxSink(it, state) }
}
