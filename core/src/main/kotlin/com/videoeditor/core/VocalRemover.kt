package com.videoeditor.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class VocalRemovalStatus { OK, MONO_SOURCE, NOTHING_LEFT, EMPTY }

/**
 * Removes the voice from a stereo recording and keeps the background music (pure Kotlin DSP,
 * on-device, no network, no ML model).
 *
 * How it works: a voice is almost always mixed to the centre, i.e. identical in the left and
 * right channel, while instruments are spread to the sides. For every short time slice and every
 * frequency the left and right channels are compared; parts that are the same in both (centre)
 * are attenuated, parts that differ (sides) are kept. Bass below ~100 Hz is kept even when
 * centred so the music does not lose its low end.
 *
 * Limits (inherent to this method): it needs a real stereo source. If left and right are
 * identical (mono, e.g. most phone recordings) nothing can be separated - that is reported as
 * [VocalRemovalStatus.MONO_SOURCE] and nothing should be saved. Instruments mixed exactly to the
 * centre are reduced together with the voice.
 *
 * Usage: call [process] with any chunk sizes, then [finish]. Output is delivered to the sink
 * aligned with the input and with exactly the same number of samples.
 */
class VocalRemover(private val sampleRate: Int) {
    private val n = if (sampleRate >= 32000) 4096 else 2048
    private val hop = n / 4
    private val window = FloatArray(n) { sqrt(0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }
    private val fft = Fft(n)

    private val inL = FloatArray(n)
    private val inR = FloatArray(n)
    private var fill = n - hop                 // start as if (n - hop) zeros were already fed
    private val accL = FloatArray(n)
    private val accR = FloatArray(n)
    private var toDiscard = (n - hop).toLong() // output belonging to the zero pre-roll
    private var totalIn = 0L
    private var totalOut = 0L

    private val lr = FloatArray(n)
    private val li = FloatArray(n)
    private val rr = FloatArray(n)
    private val ri = FloatArray(n)
    private val gainSmooth = FloatArray(n / 2 + 1) { 1f }
    private val bandWeight = FloatArray(n / 2 + 1) { k ->
        val f = k.toFloat() * sampleRate / n
        val low = ((f - 90f) / 60f).coerceIn(0f, 1f)          // keep bass: 0 below 90 Hz, full from 150 Hz
        val high = ((16000f - f) / 4000f).coerceIn(0f, 1f)    // full up to 12 kHz, off above 16 kHz
        low * high
    }
    private val outL = FloatArray(hop)
    private val outR = FloatArray(hop)

    private var sumSide = 0.0
    private var sumIn = 0.0
    private var sumOut = 0.0

    /** Feeds [count] stereo samples. [sink] receives (left, right, count) blocks of output. */
    fun process(left: FloatArray, right: FloatArray, count: Int, sink: (FloatArray, FloatArray, Int) -> Unit) {
        var i = 0
        while (i < count) {
            val take = minOf(n - fill, count - i)
            System.arraycopy(left, i, inL, fill, take)
            System.arraycopy(right, i, inR, fill, take)
            for (j in i until i + take) {
                val l = left[j]
                val r = right[j]
                val d = l - r
                sumSide += d * d
                sumIn += l * l + r * r
            }
            fill += take
            i += take
            totalIn += take
            if (fill == n) frame(sink)
        }
    }

    /** Flushes the tail. Returns the verdict; on anything but OK the output should be discarded. */
    fun finish(sink: (FloatArray, FloatArray, Int) -> Unit): VocalRemovalStatus {
        val zeros = FloatArray(hop)
        val wanted = totalIn
        var guard = 0
        while (totalOut < wanted && guard++ < 16) {
            val take = minOf(n - fill, hop)
            System.arraycopy(zeros, 0, inL, fill, take)
            System.arraycopy(zeros, 0, inR, fill, take)
            fill += take
            if (fill == n) frame(sink, limit = wanted)
        }
        if (totalIn == 0L || sumIn <= 0.0) return VocalRemovalStatus.EMPTY
        // Left and right (almost) identical => mono content, nothing to separate.
        if (sumSide / sumIn < 0.002) return VocalRemovalStatus.MONO_SOURCE
        if (sumOut / sumIn < 0.0005) return VocalRemovalStatus.NOTHING_LEFT
        return VocalRemovalStatus.OK
    }

    private fun frame(sink: (FloatArray, FloatArray, Int) -> Unit, limit: Long = Long.MAX_VALUE) {
        for (i in 0 until n) {
            lr[i] = inL[i] * window[i]; li[i] = 0f
            rr[i] = inR[i] * window[i]; ri[i] = 0f
        }
        fft.forward(lr, li)
        fft.forward(rr, ri)
        for (k in 0..n / 2) {
            val power = lr[k] * lr[k] + li[k] * li[k] + rr[k] * rr[k] + ri[k] * ri[k] + 1e-20f
            // 1 = identical in both channels (centre), 0 or below = unrelated / opposite (sides)
            val centre = (2f * (lr[k] * rr[k] + li[k] * ri[k]) / power).coerceIn(0f, 1f)
            val c2 = centre * centre
            val raw = 1f - bandWeight[k] * c2 * c2
            val g = 0.5f * gainSmooth[k] + 0.5f * raw
            gainSmooth[k] = g
            lr[k] *= g; li[k] *= g
            rr[k] *= g; ri[k] *= g
            if (k in 1 until n / 2) {          // keep the spectrum conjugate-symmetric
                val m = n - k
                lr[m] = lr[k]; li[m] = -li[k]
                rr[m] = rr[k]; ri[m] = -ri[k]
            }
        }
        fft.inverse(lr, li)
        fft.inverse(rr, ri)
        // sqrt-Hann analysis * sqrt-Hann synthesis at 75 % overlap sums to 2
        for (i in 0 until n) {
            accL[i] += lr[i] * window[i] * 0.5f
            accR[i] += rr[i] * window[i] * 0.5f
        }
        // the first hop samples are complete now
        var from = 0
        if (toDiscard > 0) {
            val skip = minOf(toDiscard, hop.toLong()).toInt()
            toDiscard -= skip
            from = skip
        }
        var count = hop - from
        if (totalOut + count > limit) count = (limit - totalOut).toInt().coerceAtLeast(0)
        if (count > 0) {
            for (i in 0 until count) {
                val l = accL[from + i]
                val r = accR[from + i]
                outL[i] = l
                outR[i] = r
                sumOut += l * l + r * r
            }
            totalOut += count
            sink(outL, outR, count)
        }
        System.arraycopy(accL, hop, accL, 0, n - hop)
        System.arraycopy(accR, hop, accR, 0, n - hop)
        java.util.Arrays.fill(accL, n - hop, n, 0f)
        java.util.Arrays.fill(accR, n - hop, n, 0f)
        System.arraycopy(inL, hop, inL, 0, n - hop)
        System.arraycopy(inR, hop, inR, 0, n - hop)
        fill = n - hop
    }
}

/** Radix-2 complex FFT with precomputed tables. */
class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
    private val rev = IntArray(n)

    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, false)

    fun inverse(re: FloatArray, im: FloatArray) {
        transform(re, im, true)
        val s = 1f / n
        for (i in 0 until n) {
            re[i] *= s
            im[i] *= s
        }
    }

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var t = 0
                for (k in 0 until half) {
                    val wr = cosT[t]
                    val wi = if (inverse) sinT[t] else -sinT[t]
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    t += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
