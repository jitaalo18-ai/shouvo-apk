package com.videoeditor.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Sound settings of a clip (the Voice enhance panel). The defaults change nothing. */
data class AudioFx(
    /** 0..2 (0 % .. 200 %) */
    val volume: Float = 1f,
    /** Reduce noise, 0..1 */
    val denoise: Float = 0f,
    /** Enhance voice, 0..1 */
    val enhance: Float = 0f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
) {
    val isDefault: Boolean
        get() = volume == 1f && denoise == 0f && enhance == 0f && fadeInMs == 0L && fadeOutMs == 0L

    /** Only the sound processing (noise / voice), without volume and fades. */
    val hasEnhancement: Boolean get() = denoise > 0f || enhance > 0f

    /** Compact text form for the database ("" = defaults). */
    fun encode(): String = if (isDefault) "" else "$volume,$denoise,$enhance,$fadeInMs,$fadeOutMs"

    companion object {
        val NONE = AudioFx()
        const val MAX_VOLUME = 2f
        /** What "Remove noise" sets (Edits uses 70 %). */
        const val AUTO_DENOISE = 0.7f

        fun decode(s: String?): AudioFx {
            if (s.isNullOrBlank()) return NONE
            val p = s.split(',')
            return try {
                AudioFx(
                    volume = p[0].toFloat().coerceIn(0f, MAX_VOLUME),
                    denoise = p[1].toFloat().coerceIn(0f, 1f),
                    enhance = p[2].toFloat().coerceIn(0f, 1f),
                    fadeInMs = p[3].toLong().coerceAtLeast(0),
                    fadeOutMs = p[4].toLong().coerceAtLeast(0),
                )
            } catch (e: RuntimeException) {
                NONE
            }
        }
    }
}

/**
 * Where a clip's sound settings apply: [startUs]..[endUs] in the time of the stream the processor
 * sees (timeline time for the main preview player, clip time for one exported clip, source time
 * for a sound effect's own player). Fades run from [clipStartUs] and up to [clipEndUs]. The noise
 * picture is learned per [key] (one per clip), so jumping between clips does not mix them up.
 */
data class FxSegment(
    val key: String,
    val startUs: Long,
    val endUs: Long,
    val fx: AudioFx,
    val clipStartUs: Long = startUs,
    val clipEndUs: Long = endUs,
)

/**
 * The Voice enhance sound processing in plain Kotlin, shared by the preview and the export and
 * tested on the JVM. Interleaved float samples in; the same samples out, later.
 *
 * Chain: noise reduction (short-time spectrum, minimum-statistics noise estimate, decision-
 * directed Wiener gain, at most [MAX_REDUCTION_DB] less noise) -> voice EQ (high-pass 80 Hz, less
 * 250 Hz mud, more 3 kHz presence and air) with a compressor -> volume x fades -> soft limiter
 * (only when something could go over full scale).
 *
 * With [compensate] (export) output sample i belongs to input sample i: the first [latency]
 * samples are held back and [drain] gives them out at the end. Without it (live playback)
 * output sample i belongs to input sample i - [latency].
 */
class VoiceFx(val sampleRate: Int, val channels: Int, private val compensate: Boolean = true) {
    private val n = if (sampleRate >= 32_000) 1024 else 512
    private val hop = n / 2
    /** Delay of the noise reduction in frames (removed with [compensate]). */
    val latency = n - hop

    /** Settings over time; replace at any moment (from any thread). */
    @Volatile
    var plan: List<FxSegment> = emptyList()

    /** True when nothing in [plan] changes the sound (the processor can be skipped). */
    val isNeutral: Boolean get() = plan.all { it.fx.isDefault }

    // ---- positions: markers (input frame index -> position in µs) ----
    private var inputFrames = 0L
    private var outputFrames = 0L
    private val outBase = if (compensate) 0L else -latency.toLong()
    private var markIndex = LongArray(16)
    private var markPos = LongArray(16)
    private var marks = 0
    private var skip = if (compensate) latency else 0

    // ---- short-time spectrum ----
    private val window = FloatArray(n) { sqrt(0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }   // sqrt periodic Hann
    private val frameIn = Array(channels) { FloatArray(n) }
    private val ola = Array(channels) { FloatArray(n) }
    private var fill = 0
    private val re = Array(channels) { FloatArray(n) }
    private val im = Array(channels) { FloatArray(n) }
    private val bins = n / 2 + 1
    private val power = FloatArray(bins)
    private val gain = FloatArray(bins)
    private val fft = Fft(n)
    private val states = LinkedHashMap<String, NoiseState>(16, 0.75f, true)
    private val pending = ArrayDeque<FloatArray>()

    // ---- EQ / dynamics ----
    private val eq = Array(channels) { Array(4) { Biquad() } }
    private var eqAmount = -1f
    private var env = 0f
    private val attack = exp(-1.0 / (0.005 * sampleRate)).toFloat()
    private val release = exp(-1.0 / (0.15 * sampleRate)).toFloat()
    private var segIndex = 0

    /** The noise picture of one clip. */
    private inner class NoiseState {
        val smooth = FloatArray(bins)
        val noise = FloatArray(bins)
        val subMin = Array(SUBWINDOWS) { FloatArray(bins) { Float.MAX_VALUE } }
        var subFrames = 0
        var subIndex = 0
        val prevGain = FloatArray(bins) { 1f }
        val prevSnr = FloatArray(bins) { 1f }
        var frames = 0
    }

    /** Frames of the noise picture learned before the reduction is at full strength. */
    private val warmupFrames = max(1, (WARMUP_SECONDS * sampleRate / hop).toInt())
    private val framesPerSub = max(1, (SUBWINDOW_SECONDS * sampleRate / hop).toInt())

    /**
     * Forgets the sound in flight (after a seek). What was learned about each clip's noise is
     * kept unless [forgetNoise].
     */
    fun reset(forgetNoise: Boolean = false) {
        inputFrames = 0
        outputFrames = 0
        marks = 0
        skip = if (compensate) latency else 0
        for (c in 0 until channels) {
            frameIn[c].fill(0f)
            ola[c].fill(0f)
            for (b in eq[c]) b.clear()
        }
        fill = 0
        pending.clear()
        env = 0f
        segIndex = 0
        if (forgetNoise) states.clear()
        for (s in states.values) {
            // the reduction eases in again after a jump, but from what is already known
            s.prevGain.fill(1f)
            s.prevSnr.fill(1f)
        }
    }

    /**
     * Takes [frames] interleaved frames of [input]; the first one is at [positionUs]. Returns the
     * output frames that are ready (a multiple of the internal block size).
     */
    fun process(input: FloatArray, frames: Int, positionUs: Long): FloatArray {
        addMark(inputFrames, positionUs)
        var i = 0
        while (i < frames) {
            val take = min(hop - fill, frames - i)
            for (c in 0 until channels) {
                val buf = frameIn[c]
                val at = hop + fill
                for (k in 0 until take) buf[at + k] = input[(i + k) * channels + c]
            }
            fill += take
            i += take
            inputFrames += take
            if (fill == hop) {
                runFrame()
                fill = 0
            }
        }
        val out = collect()
        trimMarks()
        return out
    }

    /** At the end of the stream: the frames still inside (only with [compensate]). */
    fun drain(): FloatArray {
        val want = inputFrames - (outputFrames + outBase)
        if (want <= 0) return FloatArray(0)
        val pos = positionOfInput(inputFrames)
        val zeros = 2 * hop
        val res = process(FloatArray(zeros * channels), zeros, pos)
        return if (res.size > want * channels) res.copyOf((want * channels).toInt()) else res
    }

    // ---- one spectrum frame ------------------------------------------------------------------

    private fun runFrame() {
        val seg = segmentAt(positionOfInput(inputFrames - hop))
        val strength = seg?.fx?.denoise ?: 0f
        if (strength <= 0f || seg == null) {
            // no noise reduction here: window twice (= Hann), overlap-add gives the input back
            for (c in 0 until channels) {
                val buf = frameIn[c]
                val o = ola[c]
                for (k in 0 until n) o[k] += buf[k] * window[k] * window[k]
            }
        } else {
            reduceNoise(stateFor(seg.key), strength)
        }
        val block = FloatArray(hop * channels)
        for (c in 0 until channels) {
            val o = ola[c]
            for (k in 0 until hop) block[k * channels + c] = o[k]
            System.arraycopy(o, hop, o, 0, n - hop)
            for (k in n - hop until n) o[k] = 0f
            val buf = frameIn[c]
            System.arraycopy(buf, hop, buf, 0, n - hop)
        }
        pending.addLast(block)
    }

    private fun stateFor(key: String): NoiseState {
        states[key]?.let { return it }
        if (states.size >= MAX_STATES) states.remove(states.keys.first())
        return NoiseState().also { states[key] = it }
    }

    private fun reduceNoise(s: NoiseState, strength: Float) {
        for (c in 0 until channels) {
            val r = re[c]
            val m = im[c]
            val buf = frameIn[c]
            for (k in 0 until n) {
                r[k] = buf[k] * window[k]
                m[k] = 0f
            }
            fft.forward(r, m)
        }
        for (b in 0 until bins) {
            var p = 0f
            for (c in 0 until channels) p += re[c][b] * re[c][b] + im[c][b] * im[c][b]
            p /= channels
            power[b] = p
            s.smooth[b] = if (s.frames == 0) p else SMOOTHING * s.smooth[b] + (1 - SMOOTHING) * p
            val sm = s.subMin[s.subIndex]
            if (s.smooth[b] < sm[b]) sm[b] = s.smooth[b]
            var lowest = Float.MAX_VALUE
            for (w in s.subMin) if (w[b] < lowest) lowest = w[b]
            s.noise[b] = lowest * NOISE_BIAS
        }
        s.frames++
        if (++s.subFrames >= framesPerSub) {
            s.subFrames = 0
            s.subIndex = (s.subIndex + 1) % SUBWINDOWS
            s.subMin[s.subIndex].fill(Float.MAX_VALUE)
        }
        // eases in while the noise picture is still young
        val effective = strength * min(1f, s.frames.toFloat() / warmupFrames)
        val floor = 10f.pow(-(MAX_REDUCTION_DB * effective) / 20f)
        for (b in 0 until bins) {
            val nz = max(s.noise[b], 1e-12f)
            val post = power[b] / nz
            // decision-directed a-priori SNR (Ephraim-Malah) -> Wiener gain
            val prio = DD_ALPHA * s.prevGain[b] * s.prevGain[b] * s.prevSnr[b] + (1 - DD_ALPHA) * max(post - 1f, 0f)
            gain[b] = max(prio / (1f + prio), floor)
            s.prevSnr[b] = post
        }
        // smooth across neighbouring bins (fewer "musical noise" artefacts)
        var last = gain[0]
        for (b in 1 until bins - 1) {
            val g = (last + 2f * gain[b] + gain[b + 1]) / 4f
            last = gain[b]
            gain[b] = g
        }
        for (b in 0 until bins) s.prevGain[b] = gain[b]
        for (c in 0 until channels) {
            val r = re[c]
            val m = im[c]
            for (b in 0 until bins) {
                r[b] *= gain[b]
                m[b] *= gain[b]
            }
            for (b in 1 until n / 2) {
                r[n - b] = r[b]
                m[n - b] = -m[b]
            }
            fft.inverse(r, m)
            val o = ola[c]
            for (k in 0 until n) o[k] += r[k] * window[k]
        }
    }

    // ---- after the noise reduction: EQ, compressor, volume, fades, limiter ------------------

    private fun collect(): FloatArray {
        var total = 0
        val ready = ArrayList<FloatArray>(pending.size)
        while (pending.isNotEmpty()) {
            var block = pending.removeFirst()
            if (skip > 0) {
                val frames = block.size / channels
                val drop = min(skip, frames)
                skip -= drop
                if (drop == frames) continue
                block = block.copyOfRange(drop * channels, block.size)
            }
            finish(block)
            ready += block
            total += block.size
        }
        if (ready.size == 1) return ready[0]
        val out = FloatArray(total)
        var at = 0
        for (b in ready) {
            System.arraycopy(b, 0, out, at, b.size)
            at += b.size
        }
        return out
    }

    private fun finish(block: FloatArray) {
        val frames = block.size / channels
        for (f in 0 until frames) {
            val pos = positionOfInput(outputFrames + outBase)
            outputFrames++
            val seg = segmentAt(pos) ?: continue
            val fx = seg.fx
            if (fx.isDefault) continue
            val at = f * channels
            if (fx.enhance > 0f) {
                if (fx.enhance != eqAmount) setEq(fx.enhance)
                var peak = 0f
                for (c in 0 until channels) {
                    var x = block[at + c]
                    for (b in eq[c]) x = b.run(x)
                    block[at + c] = x
                    peak = max(peak, abs(x))
                }
                // compressor on the loudest channel, then make-up gain
                env = if (peak > env) attack * env + (1 - attack) * peak else release * env + (1 - release) * peak
                val levelDb = 20f * log10(max(env, 1e-6f))
                val ratio = 1f + 3f * fx.enhance
                val over = levelDb - COMP_THRESHOLD_DB
                val reduce = if (over > 0) over * (1f - 1f / ratio) else 0f
                val g = 10f.pow((MAKEUP_DB * fx.enhance - reduce) / 20f)
                for (c in 0 until channels) block[at + c] *= g
            }
            var g = fx.volume
            if (fx.fadeInMs > 0) {
                val t = (pos - seg.clipStartUs) / (fx.fadeInMs * 1000f)
                if (t < 1f) g *= t.coerceIn(0f, 1f)
            }
            if (fx.fadeOutMs > 0) {
                val t = (seg.clipEndUs - pos) / (fx.fadeOutMs * 1000f)
                if (t < 1f) g *= t.coerceIn(0f, 1f)
            }
            val limit = fx.volume > 1f || fx.enhance > 0f
            for (c in 0 until channels) {
                val y = block[at + c] * g
                block[at + c] = if (limit) softClip(y) else y
            }
        }
    }

    private fun setEq(amount: Float) {
        eqAmount = amount
        for (c in 0 until channels) {
            val s = eq[c]
            s[0].highPass(80.0, 0.707, sampleRate)
            s[1].peaking(250.0, 1.0, -3.0 * amount, sampleRate)
            s[2].peaking(3_000.0, 0.8, 5.0 * amount, sampleRate)
            s[3].highShelf(min(8_000.0, sampleRate * 0.4), 3.0 * amount, sampleRate)
        }
    }

    // ---- positions ----------------------------------------------------------------------------

    private fun addMark(index: Long, pos: Long) {
        if (marks == markIndex.size) {
            markIndex = markIndex.copyOf(marks * 2)
            markPos = markPos.copyOf(marks * 2)
        }
        markIndex[marks] = index
        markPos[marks] = pos
        marks++
    }

    /** Drops markers that no output frame needs any more. */
    private fun trimMarks() {
        val oldest = outputFrames + outBase
        var drop = 0
        while (drop + 1 < marks && markIndex[drop + 1] <= oldest) drop++
        if (drop > 0) {
            System.arraycopy(markIndex, drop, markIndex, 0, marks - drop)
            System.arraycopy(markPos, drop, markPos, 0, marks - drop)
            marks -= drop
        }
    }

    /** Position (µs) of input frame [index]. */
    private fun positionOfInput(index: Long): Long {
        if (marks == 0) return 0
        var i = marks - 1
        while (i > 0 && markIndex[i] > index) i--
        return markPos[i] + ((index - markIndex[i]) * 1_000_000L + sampleRate / 2) / sampleRate
    }

    private fun segmentAt(posUs: Long): FxSegment? {
        val p = plan
        if (p.isEmpty()) return null
        if (segIndex >= p.size) segIndex = 0
        val cur = p[segIndex]
        if (posUs >= cur.startUs && posUs < cur.endUs) return cur
        if (segIndex + 1 < p.size) {
            val next = p[segIndex + 1]
            if (posUs >= next.startUs && posUs < next.endUs) {
                segIndex++
                return next
            }
        }
        for (k in p.indices) {
            val s = p[k]
            if (posUs >= s.startUs && posUs < s.endUs) {
                segIndex = k
                return s
            }
        }
        return null
    }

    companion object {
        const val MAX_REDUCTION_DB = 28f
        const val NOISE_BIAS = 2.2f
        const val SMOOTHING = 0.7f
        const val DD_ALPHA = 0.96f
        const val SUBWINDOWS = 8
        const val SUBWINDOW_SECONDS = 0.2f
        const val WARMUP_SECONDS = 0.6f
        const val MAX_STATES = 48
        const val COMP_THRESHOLD_DB = -24f
        const val MAKEUP_DB = 6f
        const val KNEE = 0.9f
        const val CEILING = 0.99f

        private fun log10(x: Float): Float = (ln(x.toDouble()) / LN10).toFloat()
        private val LN10 = ln(10.0)

        /** Smoothly bends samples above [KNEE] so they stay under [CEILING]. */
        fun softClip(x: Float): Float {
            val a = abs(x)
            if (a <= KNEE) return x
            val y = KNEE + (CEILING - KNEE) * tanh(((a - KNEE) / (CEILING - KNEE)).toDouble()).toFloat()
            return if (x < 0) -y else y
        }
    }
}

/** One second-order filter (RBJ audio EQ cookbook). */
private class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    fun clear() {
        z1 = 0.0
        z2 = 0.0
    }

    fun run(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y.toFloat()
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0
    }

    fun highPass(f: Double, q: Double, rate: Int) {
        val w = 2 * PI * f / rate
        val alpha = sin(w) / (2 * q)
        val c = cos(w)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    fun peaking(f: Double, q: Double, db: Double, rate: Int) {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * f / rate
        val alpha = sin(w) / (2 * q)
        val c = cos(w)
        set(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    fun highShelf(f: Double, db: Double, rate: Int) {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * f / rate
        val c = cos(w)
        val alpha = sin(w) / 2 * sqrt(2.0)
        val sa = 2 * sqrt(a) * alpha
        set(
            a * ((a + 1) + (a - 1) * c + sa),
            -2 * a * ((a - 1) + (a + 1) * c),
            a * ((a + 1) + (a - 1) * c - sa),
            (a + 1) - (a - 1) * c + sa,
            2 * ((a - 1) - (a + 1) * c),
            (a + 1) - (a - 1) * c - sa,
        )
    }
}

/**
 * Loudness and noise of one clip's sound (for the Auto tab). Levels in dBFS (RMS); [flatness]
 * tells hiss-like noise (near 1) from a steady tone or music (near 0) in the quiet parts.
 */
data class AudioLevel(val noiseDb: Float, val speechDb: Float, val flatness: Float = 1f) {
    val hasSound: Boolean get() = speechDb > AudioAnalysis.SILENT_DB
    /** A steady, hiss-like noise floor loud enough to hear, with a voice above it. */
    val isNoisy: Boolean
        get() = hasSound && noiseDb > AudioAnalysis.NOISY_FLOOR_DB && flatness >= AudioAnalysis.NOISE_FLATNESS &&
            speechDb - noiseDb >= AudioAnalysis.VOICE_OVER_NOISE_DB
}

object AudioAnalysis {
    const val SILENT_DB = -60f
    const val NOISY_FLOOR_DB = -50f
    const val NOISE_FLATNESS = 0.2f
    const val VOICE_OVER_NOISE_DB = 4f
    /** Where "Auto adjust audio" brings the voice of every clip. */
    const val TARGET_DB = -20f
    const val MIN_AUTO_VOLUME = 0.25f

    /**
     * Measures mono [samples]: noise = the quiet 10 % of 20 ms frames (and how flat their
     * spectrum is), speech = the frames clearly above that.
     */
    fun measure(samples: FloatArray, rate: Int): AudioLevel {
        val frame = max(1, rate / 50)
        val count = samples.size / frame
        if (count == 0) return AudioLevel(-100f, -100f, 0f)
        val db = FloatArray(count)
        val pw = DoubleArray(count)
        for (f in 0 until count) {
            var s = 0.0
            for (k in f * frame until (f + 1) * frame) s += samples[k].toDouble() * samples[k]
            pw[f] = s / frame
            db[f] = (10 * kotlin.math.log10(max(pw[f], 1e-10))).toFloat()
        }
        val order = (0 until count).sortedBy { db[it] }
        val quiet = order.take(max(1, count / 10))
        val noise = db[quiet.last()]
        val gate = max(noise + 10f, SILENT_DB)
        var sum = 0.0
        var active = 0
        for (f in 0 until count) if (db[f] >= gate) {
            sum += pw[f]
            active++
        }
        // nothing stands out (a steady tone or hum): its level is the level of the whole clip
        val speech = (10 * kotlin.math.log10(max(if (active == 0) pw.average() else sum / active, 1e-10))).toFloat()
        return AudioLevel(noise, speech, flatness(samples, rate, quiet.map { it * frame }))
    }

    /** Spectral flatness (geometric / arithmetic mean, 100 Hz..7 kHz) of the average spectrum at [starts]. */
    private fun flatness(samples: FloatArray, rate: Int, starts: List<Int>): Float {
        val n = 512
        val fft = Fft(n)
        val re = FloatArray(n)
        val im = FloatArray(n)
        val acc = DoubleArray(n / 2 + 1)
        var used = 0
        for (st in starts) {
            if (st + n > samples.size) continue
            for (k in 0 until n) {
                re[k] = samples[st + k] * (0.5f - 0.5f * cos(2.0 * PI * k / n).toFloat())
                im[k] = 0f
            }
            fft.forward(re, im)
            for (b in acc.indices) acc[b] += (re[b] * re[b] + im[b] * im[b]).toDouble()
            used++
        }
        if (used == 0) return 0f
        val lo = max(1, (100.0 * n / rate).toInt())
        val hi = min(n / 2, (7_000.0 * n / rate).toInt())
        if (hi <= lo) return 0f
        var logSum = 0.0
        var linSum = 0.0
        for (b in lo..hi) {
            val p = acc[b] / used + 1e-20
            logSum += ln(p)
            linSum += p
        }
        val m = hi - lo + 1
        return (exp(logSum / m) / (linSum / m)).toFloat()
    }

    /** Volume that brings [level]'s voice to [TARGET_DB] (rounded to 5 %), or null if silent. */
    fun autoVolume(level: AudioLevel): Float? {
        if (!level.hasSound) return null
        val v = 10f.pow((TARGET_DB - level.speechDb) / 20f).coerceIn(MIN_AUTO_VOLUME, AudioFx.MAX_VOLUME)
        return (Math.round(v * 20f) / 20f)
    }
}

/**
 * Finds which clip a piece of decoded sound belongs to in the main preview player.
 *
 * The player's audio arrives with renderer times. For every playlist item, renderer time =
 * stream offset + time in the source file (clipped items keep source times), and the items of one
 * playing queue follow each other without gaps, so renderer time - timeline time is the same for
 * the whole queue. That difference is learned from the first sound after a seek (with the source
 * time and the seek target to tell clips apart) and kept until the next seek.
 */
class PlayMap {
    /** One main clip as the player plays it. */
    data class Item(
        val key: String,
        val timelineStartUs: Long,
        val sourceStartUs: Long,
        val durationUs: Long,
        val audible: Boolean,
    ) {
        val timelineEndUs: Long get() = timelineStartUs + durationUs
    }

    @Volatile
    var items: List<Item> = emptyList()

    /** The timeline time the player was last told to seek to. */
    @Volatile
    var hintUs: Long = 0

    private var difference: Long? = null

    /** A seek or flush happened: learn the mapping again from the next sound. */
    fun forget() {
        difference = null
    }

    /** Timeline time (µs) of renderer time [rendererUs], played with stream offset [offsetUs]. */
    fun locate(rendererUs: Long, offsetUs: Long): Long {
        val list = items
        val sourceUs = rendererUs - offsetUs
        val d = difference
        if (d != null) {
            val t = rendererUs - d
            val item = list.firstOrNull { t >= it.timelineStartUs - SLACK_US && t < it.timelineEndUs + SLACK_US }
            // still consistent with the source time of the item it falls into?
            if (item == null || abs((sourceUs - item.sourceStartUs) - (t - item.timelineStartUs)) <= SLACK_US) return t
        }
        // learn: the clips that play this source time; nearest to the seek target wins
        var best: Item? = null
        var bestDist = Long.MAX_VALUE
        for (it in list) {
            if (!it.audible) continue
            if (sourceUs < it.sourceStartUs - SLACK_US || sourceUs > it.sourceStartUs + it.durationUs + SLACK_US) continue
            val t = it.timelineStartUs + (sourceUs - it.sourceStartUs)
            val dist = abs(t - hintUs)
            if (dist < bestDist) {
                bestDist = dist
                best = it
            }
        }
        val b = best ?: return hintUs
        val diff = rendererUs - (b.timelineStartUs + (sourceUs - b.sourceStartUs))
        difference = diff
        return rendererUs - diff
    }

    companion object {
        const val SLACK_US = 60_000L
    }
}
