package com.videoeditor.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

enum class VadStatus { OK, NO_AUDIO, NO_SPEECH, UNRELIABLE }

data class VadConfig(
    val frameMs: Int = 20,
    /** Speech band used for the decision. */
    val bandLowHz: Int = 250,
    val bandHighHz: Int = 3600,
    /** dB above the adaptive noise floor needed to enter / stay in speech. */
    val onMarginDb: Float = 9f,
    val offMarginDb: Float = 5f,
    /** Frames quieter than this are never speech, whatever the noise floor says. */
    val absoluteMinDb: Float = -58f,
    /** If loud and quiet parts differ by less than this, the result is declared unreliable. */
    val minDynamicRangeDb: Float = 10f,
    val noiseWindowMs: Int = 10_000,
    val fillGapMs: Int = 250,
    val minSpeechMs: Int = 120,
)

data class VadResult(val status: VadStatus, val speech: List<MsRange>, val analyzedMs: Long)

/**
 * On-device voice activity detector (pure Kotlin, no network, no native code).
 *
 * It does not look for digital silence. For every 20 ms frame it measures the energy inside the
 * speech band (FFT), tracks the background-noise floor adaptively (10th percentile over a sliding
 * window, capped by the global floor), and marks a frame as speech when it rises clearly above
 * that floor, with hysteresis so word endings are not clipped. Steady background noise (fan, room
 * tone, hiss) therefore counts as non-speech.
 *
 * Feed 16-bit mono PCM with [accept] in any chunk size, then call [finish].
 */
class SpeechDetector(private val sampleRate: Int, private val cfg: VadConfig = VadConfig()) {
    private val frameLen = sampleRate * cfg.frameMs / 1000
    private val fftSize = Integer.highestOneBit(maxOf(frameLen, 2) - 1) shl 1
    private val window = FloatArray(frameLen) { (0.5 - 0.5 * cos(2.0 * PI * it / (frameLen - 1))).toFloat() }
    private val frame = ShortArray(frameLen)
    private var fill = 0
    private var bandDb = FloatArray(4096)
    private var frames = 0
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val binLow = (cfg.bandLowHz.toLong() * fftSize / sampleRate).toInt().coerceAtLeast(1)
    private val binHigh = (cfg.bandHighHz.toLong() * fftSize / sampleRate).toInt().coerceAtMost(fftSize / 2 - 1)

    init {
        require(sampleRate in 4000..48000) { "Unsupported sample rate $sampleRate" }
    }

    fun accept(samples: ShortArray, count: Int = samples.size) {
        var i = 0
        while (i < count) {
            val n = minOf(frameLen - fill, count - i)
            System.arraycopy(samples, i, frame, fill, n)
            fill += n
            i += n
            if (fill == frameLen) {
                pushFrame()
                fill = 0
            }
        }
    }

    private fun pushFrame() {
        var sumSq = 0.0
        for (i in 0 until frameLen) {
            val s = frame[i] / 32768f
            sumSq += s * s
            re[i] = s * window[i]
            im[i] = 0f
        }
        for (i in frameLen until fftSize) {
            re[i] = 0f
            im[i] = 0f
        }
        fft(re, im)
        var band = 0.0
        var total = 1e-12
        for (k in 1 until fftSize / 2) {
            val p = (re[k] * re[k] + im[k] * im[k]).toDouble()
            total += p
            if (k in binLow..binHigh) band += p
        }
        val rmsDb = 10.0 * log10(sumSq / frameLen + 1e-12)
        val db = (rmsDb + 10.0 * log10(band / total + 1e-9)).toFloat()
        if (frames == bandDb.size) bandDb = bandDb.copyOf(frames * 2)
        bandDb[frames++] = db
    }

    fun finish(): VadResult {
        val n = frames
        val analyzedMs = n.toLong() * cfg.frameMs
        if (n == 0) return VadResult(VadStatus.NO_AUDIO, emptyList(), 0)
        val db = bandDb.copyOf(n)
        val sorted = db.copyOf().also { it.sort() }
        val p10 = sorted[(n * 0.10f).toInt().coerceIn(0, n - 1)]
        val p95 = sorted[(n * 0.95f).toInt().coerceIn(0, n - 1)]
        if (p95 < cfg.absoluteMinDb) return VadResult(VadStatus.NO_SPEECH, emptyList(), analyzedMs)
        if (p95 - p10 < cfg.minDynamicRangeDb) return VadResult(VadStatus.UNRELIABLE, emptyList(), analyzedMs)

        // Adaptive noise floor: local 10th percentile, never far above the global floor so that
        // long stretches of uninterrupted talking are not mistaken for "loud background".
        val half = (cfg.noiseWindowMs / cfg.frameMs / 2).coerceAtLeast(1)
        val block = 25
        val floor = FloatArray(n)
        var b = 0
        while (b < n) {
            val centre = minOf(b + block / 2, n - 1)
            val from = maxOf(0, centre - half)
            val to = minOf(n, centre + half)
            val win = db.copyOfRange(from, to).also { it.sort() }
            val local = win[(win.size * 0.10f).toInt().coerceIn(0, win.size - 1)]
            val f = minOf(local, p10 + 6f)
            for (i in b until minOf(b + block, n)) floor[i] = f
            b += block
        }

        val speech = BooleanArray(n)
        var on = false
        for (i in 0 until n) {
            val v = db[i]
            on = if (on) v > floor[i] + cfg.offMarginDb && v > cfg.absoluteMinDb
            else v > floor[i] + cfg.onMarginDb && v > cfg.absoluteMinDb
            speech[i] = on
        }
        fillShortRuns(speech, false, cfg.fillGapMs / cfg.frameMs)   // bridge tiny gaps inside words
        fillShortRuns(speech, true, cfg.minSpeechMs / cfg.frameMs)  // drop clicks / bumps

        val ranges = ArrayList<MsRange>()
        var start = -1
        for (i in 0..n) {
            val s = i < n && speech[i]
            if (s && start < 0) start = i
            if (!s && start >= 0) {
                ranges.add(MsRange(start.toLong() * cfg.frameMs, i.toLong() * cfg.frameMs))
                start = -1
            }
        }
        if (ranges.isEmpty()) return VadResult(VadStatus.NO_SPEECH, emptyList(), analyzedMs)
        return VadResult(VadStatus.OK, ranges, analyzedMs)
    }

    /** Flips interior runs of [value] shorter than [maxLen] frames. Runs touching the edges stay. */
    private fun fillShortRuns(a: BooleanArray, value: Boolean, maxLen: Int) {
        var i = 0
        while (i < a.size) {
            if (a[i] == value) {
                var j = i
                while (j < a.size && a[j] == value) j++
                val interior = i > 0 && j < a.size
                if (j - i < maxLen && (interior || value)) for (k in i until j) a[k] = !value
                i = j
            } else i++
        }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val c = a + len / 2
                    val xr = re[c] * cr - im[c] * ci
                    val xi = re[c] * ci + im[c] * cr
                    re[c] = re[a] - xr; im[c] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Streaming down-mixer/decimator: interleaved PCM in, mono 16-bit at [outRate] out (box-filter
 * decimation, good enough for speech detection). If the input rate is already <= [outRate] the
 * samples pass through unchanged, so read [effectiveRate] for the real output rate.
 */
class MonoDownsampler(private val inRate: Int, private val channels: Int, outRate: Int = 16000) {
    val effectiveRate: Int = if (inRate > outRate) outRate else inRate
    private val ratio = inRate.toDouble() / effectiveRate
    private var phase = 0.0
    private var acc = 0.0
    private var accN = 0

    /** [interleaved] holds [frames] frames of [channels] samples in -1..1. Returns samples written to [out]. */
    fun process(interleaved: FloatArray, frames: Int, out: ShortArray): Int {
        var w = 0
        for (f in 0 until frames) {
            var m = 0f
            val base = f * channels
            for (c in 0 until channels) m += interleaved[base + c]
            acc += m / channels
            accN++
            phase += 1.0
            if (phase >= ratio) {
                phase -= ratio
                val v = (acc / accN * 32767.0).coerceIn(-32768.0, 32767.0)
                out[w++] = v.toInt().toShort()
                acc = 0.0
                accN = 0
            }
        }
        return w
    }

    /** Upper bound of output samples for [frames] input frames. */
    fun maxOut(frames: Int): Int = (frames / ratio).toInt() + 2
}
