package com.videoeditor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class ResamplerTests {
    private fun runAll(r: Resampler, x: FloatArray, seed: Long = 1): FloatArray {
        val rnd = Random(seed)
        val out = ArrayList<FloatArray>()
        var at = 0
        while (at < x.size) {
            val n = minOf(x.size - at, 1 + rnd.nextInt(5000))
            out += r.feed(x.copyOfRange(at, at + n), n)
            at += n
        }
        out += r.finish()
        val all = FloatArray(out.sumOf { it.size })
        var p = 0
        for (b in out) { System.arraycopy(b, 0, all, p, b.size); p += b.size }
        return all
    }

    private fun sine(n: Int, rate: Int, hz: Double, amp: Double = 0.5) = FloatArray(n) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }

    /** Residual against the ideal signal, dB below the signal (middle part only). */
    private fun errorDb(got: FloatArray, ideal: FloatArray): Double {
        var e = 0.0
        var s = 0.0
        for (i in 200 until minOf(got.size, ideal.size) - 200) {
            e += (got[i] - ideal[i]).toDouble() * (got[i] - ideal[i])
            s += ideal[i].toDouble() * ideal[i]
        }
        return 10 * log10(e / s)
    }

    @Test fun sameRatePassesThrough() {
        val x = sine(10_000, 48_000, 440.0)
        val y = runAll(Resampler(48_000, 48_000), x)
        assertTrue(x.contentEquals(y))
    }

    @Test fun upsampling44to48IsClean() {
        for (hz in listOf(100.0, 1_000.0, 8_000.0, 15_000.0)) {
            val x = sine(44_100, 44_100, hz)
            val y = runAll(Resampler(44_100, 48_000), x)
            assertEquals(48_000, y.size)
            val err = errorDb(y, sine(48_000, 48_000, hz))
            println("44.1k -> 48k, $hz Hz: error %.1f dB".format(err))
            assertTrue("$hz Hz error $err dB", err < -60)
        }
    }

    @Test fun downsamplingKeepsTheBandAndBlocksAliasing() {
        val x = sine(48_000, 48_000, 1_000.0)
        val y = runAll(Resampler(48_000, 44_100), x)
        assertEquals(44_100, y.size)
        assertTrue(errorDb(y, sine(44_100, 44_100, 1_000.0)) < -60)
        // 23.5 kHz cannot exist at 44.1 kHz: it must not fold back as a loud alias
        val hi = runAll(Resampler(48_000, 44_100), sine(48_000, 48_000, 23_500.0))
        var p = 0.0
        for (i in 500 until hi.size - 500) p += hi[i].toDouble() * hi[i]
        val db = 10 * log10(p / (hi.size - 1000) / 0.125)
        println("23.5 kHz after 48k -> 44.1k: %.1f dB".format(db))
        assertTrue("alias $db dB", db < -40)
    }

    @Test fun noDelay() {
        val x = FloatArray(44_100)
        x[4_410] = 1f                                   // 0.1 s
        val y = runAll(Resampler(44_100, 48_000), x)
        val peak = y.indices.maxByOrNull { abs(y[it]) }!!
        assertEquals(4_800, peak)
        // other rates
        val z = FloatArray(32_000).also { it[16_000] = 1f }
        val w = runAll(Resampler(32_000, 48_000), z)
        assertEquals(24_000, w.indices.maxByOrNull { abs(w[it]) }!!)
    }

    @Test fun lengthsAddUp() {
        for ((a, b) in listOf(44_100 to 48_000, 22_050 to 48_000, 16_000 to 48_000, 96_000 to 48_000)) {
            val r = Resampler(a, b)
            val y = runAll(r, FloatArray(12_345) { 0.1f })
            assertEquals("$a -> $b", r.outputLength(12_345).toInt(), y.size)
        }
    }
}
