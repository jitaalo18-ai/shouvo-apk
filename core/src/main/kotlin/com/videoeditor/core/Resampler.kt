package com.videoeditor.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming sample-rate converter for mono sound (windowed-sinc, 32 zero crossings, Blackman
 * window, a table of 1024 phases with linear interpolation). Output sample j is the sound at
 * time j / outRate, so input and output stay exactly aligned (no delay). Equal rates pass the
 * samples through untouched.
 */
class Resampler(val inRate: Int, val outRate: Int) {
    private val identity = inRate == outRate
    private val half = HALF_TAPS
    /** Low-pass at the lower Nyquist (a little below, against aliasing). */
    private val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.96
    private val table = FloatArray((half * PHASES + 1)).also { t ->
        for (i in t.indices) {
            val x = i.toDouble() / PHASES                       // 0..half input samples
            val s = if (x == 0.0) 1.0 else sin(PI * x * cutoff) / (PI * x * cutoff)
            val w = 0.42 + 0.5 * cos(PI * x / half) + 0.08 * cos(2 * PI * x / half)   // Blackman, 0 at x = half
            t[i] = (s * w * cutoff).toFloat()
        }
    }

    // input kept: buf[0] is input sample number [bufStart]
    private var buf = FloatArray(8192)
    private var bufLen = 0
    private var bufStart = -half.toLong()      // history starts as zeros before sample 0
    private var nextOut = 0L                   // next output sample number
    private var inTotal = 0L
    private var finished = false

    init {
        bufLen = half                          // the zeros before the first sample
    }

    /** Number of output samples that belong to [inputSamples] input samples. */
    fun outputLength(inputSamples: Long): Long = (inputSamples * outRate + inRate - 1) / inRate

    /** Feeds [count] samples; returns the output samples that are ready. */
    fun feed(input: FloatArray, count: Int): FloatArray {
        if (identity) return input.copyOf(count)
        if (bufLen + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + count))
        System.arraycopy(input, 0, buf, bufLen, count)
        bufLen += count
        inTotal += count
        return produce(final = false)
    }

    /** The end: the last samples (the filter's tail is filled with silence). */
    fun finish(): FloatArray {
        if (identity || finished) return FloatArray(0)
        finished = true
        val pad = half + 1
        if (bufLen + pad > buf.size) buf = buf.copyOf(bufLen + pad)
        java.util.Arrays.fill(buf, bufLen, bufLen + pad, 0f)
        bufLen += pad
        return produce(final = true)
    }

    private fun produce(final: Boolean): FloatArray {
        val want = if (final) outputLength(inTotal) else Long.MAX_VALUE
        var arr = FloatArray(((bufLen.toLong() * outRate / inRate) + 4).toInt().coerceAtLeast(4))
        var n = 0
        while (nextOut < want) {
            // input position of this output sample, exactly: nextOut * inRate / outRate
            val num = nextOut * inRate
            val ip = num / outRate
            val frac = (num % outRate).toDouble() / outRate
            val last = ip + half                                 // newest input sample needed
            if (last - bufStart >= bufLen) break
            var acc = 0.0
            val first = ip - half + 1
            for (k in first..last) {
                val d = abs((k - ip) - frac)                     // distance in input samples
                val tpos = d * PHASES
                val ti = tpos.toInt()
                if (ti >= table.size - 1) continue
                val f = tpos - ti
                val coef = table[ti] + (table[ti + 1] - table[ti]) * f
                acc += buf[(k - bufStart).toInt()] * coef
            }
            if (n == arr.size) arr = arr.copyOf(arr.size * 2)
            arr[n++] = acc.toFloat()
            nextOut++
        }
        // keep what the next output samples still need
        val keepFrom = (nextOut * inRate / outRate) - half + 1
        val drop = (keepFrom - bufStart).coerceIn(0, bufLen.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
        return arr.copyOf(n)
    }

    companion object {
        const val HALF_TAPS = 32
        const val PHASES = 1024
    }
}
