package com.videoeditor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class SceneDetectTests {
    private val w = SceneDetector.W
    private val h = SceneDetector.H

    /** A shot: its own pattern (by [seed]) that drifts sideways with [speed] px per frame, plus noise. */
    private fun frame(seed: Int, n: Int, speed: Float = 0.5f, noise: Int = 6): ByteArray {
        val rnd = Random(seed * 7919L + n)
        val base = Random(seed.toLong())
        val fx = 0.15 + base.nextDouble() * 0.5
        val fy = 0.15 + base.nextDouble() * 0.5
        val level = 60 + base.nextInt(120)
        val amp = 30 + base.nextInt(60)
        return ByteArray(w * h) { i ->
            val x = i % w + n * speed
            val y = i / w
            val v = level + amp * sin(2 * PI * (fx * x / 8 + fy * y / 8)) + rnd.nextInt(2 * noise + 1) - noise
            v.toInt().coerceIn(0, 255).toByte()
        }
    }

    private fun detect(shots: List<Triple<Int, Int, Float>>, fps: Int = 30): List<Long> {
        val d = SceneDetector()
        var n = 0
        for ((seed, frames, speed) in shots) repeat(frames) { k ->
            d.feed(n * 1000L / fps, frame(seed, k, speed))
            n++
        }
        return d.finish(n * 1000L / fps)
    }

    @Test fun findsEveryHardCutAtItsFrame() {
        val cuts = detect(listOf(Triple(1, 60, 0.5f), Triple(2, 45, 0.5f), Triple(3, 90, 0.5f), Triple(4, 30, 0.5f)))
        assertEquals(listOf(2000L, 3500L, 6500L), cuts)
    }

    @Test fun motionAndPansInsideAShotAreNotCuts() {
        assertEquals(emptyList<Long>(), detect(listOf(Triple(5, 150, 0.5f))))
        assertEquals("fast pan", emptyList<Long>(), detect(listOf(Triple(6, 150, 3f))))
        // slowly getting brighter
        val d = SceneDetector()
        for (n in 0 until 120) d.feed(n * 33L, ByteArray(w * h) { (40 + n + (it % 7)).toByte() })
        assertEquals(emptyList<Long>(), d.finish(4000))
    }

    @Test fun aSingleFlashFrameIsNotACut() {
        val d = SceneDetector()
        for (n in 0 until 90) d.feed(n * 1000L / 30, if (n == 45) ByteArray(w * h) { 250.toByte() } else frame(7, n))
        assertEquals(emptyList<Long>(), d.finish(3000))
    }

    @Test fun veryShortShotsAndTheVeryEndAreLeftAlone() {
        // a 5-frame shot (167 ms): no cut into it; the cut after it is far enough from the last one
        val cuts = detect(listOf(Triple(1, 60, 0.5f), Triple(2, 5, 0.5f), Triple(3, 60, 0.5f)))
        assertEquals(1, cuts.size)
        // a cut 200 ms before the end is dropped
        assertEquals(emptyList<Long>(), detect(listOf(Triple(1, 60, 0.5f), Triple(2, 6, 0.5f))))
    }

    @Test fun splitsTheClipAtTheCutsInOneGo() {
        val clip = Clip(
            id = "v", track = Track.MAIN, kind = MediaKind.VIDEO, sourceUri = "file:///v.mp4", sourceDurationMs = 20_000,
            sourceStartMs = 3_000, sourceEndMs = 13_000, timelineStartMs = 0, hasAudio = true,
        )
        val other = clip.copy(id = "w", timelineStartMs = 10_000)
        val t = TimelineOps.normalize(Timeline(listOf(clip, other)))
        var n = 0
        val (out, pieces) = SceneSplit.apply(t, "v", listOf(6_500L, 2_000L, 3_500L, 9_990L), { "p${n++}" })
        assertEquals(4, pieces)                                  // the cut 10 ms before the end is skipped
        val main = out.main
        assertEquals(listOf("v", "p0", "p1", "p2", "w"), main.map { it.id })
        assertEquals(listOf(0L, 2_000L, 3_500L, 6_500L, 10_000L), main.map { it.timelineStartMs })
        assertEquals(listOf(3_000L, 5_000L, 6_500L, 9_500L), main.take(4).map { it.sourceStartMs })
        assertEquals(13_000L, main[3].sourceEndMs)
        assertEquals(t.durationMs, out.durationMs)
        assertTrue(main.zipWithNext().all { (a, b) -> a.timelineEndMs == b.timelineStartMs })
        val (same, one) = SceneSplit.apply(t, "v", emptyList(), { "x" })
        assertEquals(1, one)
        assertEquals(t, same)
    }
}
