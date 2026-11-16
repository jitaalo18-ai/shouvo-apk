package com.videoeditor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSettingsTests {
    @Test fun frameSizesForEveryResolutionAndShape() {
        assertEquals(1920 to 1080, ExportSettings().canvasSize(16f / 9f))
        assertEquals(1080 to 1920, ExportSettings().canvasSize(9f / 16f))
        assertEquals(1080 to 1080, ExportSettings().canvasSize(1f))
        assertEquals(1080 to 1350, ExportSettings().canvasSize(4f / 5f))
        assertEquals(2560 to 1440, ExportSettings(1440).canvasSize(16f / 9f))
        assertEquals(1440 to 2560, ExportSettings(1440).canvasSize(9f / 16f))
        assertEquals(3840 to 2160, ExportSettings(2160).canvasSize(16f / 9f))
        assertEquals(2160 to 3840, ExportSettings(2160).canvasSize(9f / 16f))
        assertEquals(1280 to 720, ExportSettings(720).canvasSize(16f / 9f))
        assertEquals(854 to 480, ExportSettings(480).canvasSize(16f / 9f))
        assertEquals(480 to 854, ExportSettings(480).canvasSize(9f / 16f))
        // wider than 16:9: the long side is capped, the shape kept
        val (w, h) = ExportSettings(1440).canvasSize(2.4f)
        assertEquals(2560, w)
        assertEquals(2.4f, w.toFloat() / h, 0.01f)
        for (r in ExportSettings.RESOLUTIONS) for (a in listOf(0.4f, 0.5625f, 0.8f, 1f, 1.5f, 1.7778f, 2.4f)) {
            val (cw, ch) = ExportSettings(r.first).canvasSize(a)
            assertTrue(cw % 2 == 0 && ch % 2 == 0 && minOf(cw, ch) <= r.first && maxOf(cw, ch) <= r.first * 16 / 9 + 2)
        }
    }

    @Test fun labelsAndStorage() {
        assertEquals("FHD", ExportSettings().badge)
        assertEquals("2K", ExportSettings(1440).badge)
        assertEquals("4K", ExportSettings(2160, 25).resolutionLabel)
        assertEquals("Auto", ExportSettings().fpsLabel)
        val s = ExportSettings(1440, 25)
        assertEquals(s, ExportSettings.decode(s.encode()))
        assertEquals(ExportSettings(), ExportSettings.decode(null))
        assertEquals(ExportSettings(), ExportSettings.decode("999,17"))
        assertEquals(ExportSettings(720, 0), ExportSettings.decode("720,x"))
        assertTrue(ExportSettings().isDefault)
        assertFalse(ExportSettings(1080, 30).isDefault)
    }

    private fun clip(id: String, start: Long, dur: Long, track: Track = Track.MAIN, kind: MediaKind = MediaKind.VIDEO, src: Long = 0) = Clip(
        id = id, track = track, kind = kind, sourceUri = "file:///$id", sourceDurationMs = if (kind == MediaKind.VIDEO || kind == MediaKind.AUDIO) src + dur + 5_000 else 0,
        sourceStartMs = src, sourceEndMs = src + dur, timelineStartMs = start, hasAudio = kind != MediaKind.IMAGE,
    )

    @Test fun exportVideoTakesExactlyThePartUnderTheClip() {
        val t = Timeline(listOf(
            clip("a", 0, 4_000, src = 1_000),
            clip("b", 4_000, 2_600, src = 20_000),
            clip("c", 6_600, 3_000),
            clip("music", 1_000, 8_000, Track.MUSIC, MediaKind.AUDIO).let { it.copy(audio = it.audio.copy(fadeInMs = 500, fadeOutMs = 700)) },
            clip("pic", 5_000, 1_000, Track.OVERLAY, MediaKind.IMAGE),
            clip("early", 3_000, 1_500, Track.OVERLAY, MediaKind.IMAGE),       // reaches 0.5 s into b
            clip("after", 7_000, 1_000, Track.SFX, MediaKind.AUDIO),
        ))
        val out = ExportRange.ofClip(t, "b")!!
        assertEquals(2_600L, out.durationMs)
        assertEquals(listOf("b"), out.main.map { it.id })
        val b = out.byId("b")!!
        assertEquals(0L, b.timelineStartMs)
        assertEquals(20_000L, b.sourceStartMs)
        assertEquals(22_600L, b.sourceEndMs)
        // the music under it: the same sound that played there, no fades (both ends were cut)
        val m = out.byId("music")!!
        assertEquals(0L, m.timelineStartMs)
        assertEquals(3_000L, m.sourceStartMs)
        assertEquals(5_600L, m.sourceEndMs)
        assertEquals(0L, m.audio.fadeInMs)
        assertEquals(0L, m.audio.fadeOutMs)
        // overlays keep their place in time
        assertEquals(1_000L, out.byId("pic")!!.timelineStartMs)
        assertEquals(1_000L, out.byId("pic")!!.durationMs)
        assertEquals(0L, out.byId("early")!!.timelineStartMs)
        assertEquals(500L, out.byId("early")!!.durationMs)
        assertNull(out.byId("a"))
        assertNull(out.byId("c"))
        assertNull(out.byId("after"))
        assertFalse(out.clips.any { it.selected })
        assertNull(ExportRange.ofClip(t, "nope"))
        // the whole project as a range is the project
        assertEquals(t.clips.map { it.id to it.timelineStartMs }.toSet(), ExportRange.slice(t, 0, t.durationMs).clips.map { it.id to it.timelineStartMs }.toSet())
    }

    @Test fun chosenSettingsNeverCopyTheOldPicture() {
        val c = clip("v", 0, 5_000).copy(sourceDurationMs = 5_000, aspect = 16f / 9f, audio = AudioFx(isolate = true))
        val t = Timeline(listOf(c))
        assertTrue(ExportPolicy.canCopyVideo(t, 16f / 9f))
        assertFalse(ExportPolicy.canCopyVideo(t, 16f / 9f, ExportSettings(720)))
        assertFalse(ExportPolicy.canCopyVideo(t, 16f / 9f, ExportSettings(1080, 30)))
    }
}

class FrameRateLimiterTests {
    private fun kept(sourceFps: Double, target: Int, seconds: Int = 10): List<Long> {
        val l = FrameRateLimiter(target)
        return (0 until (sourceFps * seconds).toInt()).map { Math.round(it * 1_000_000 / sourceFps) }.filter { l.keep(it) }
    }

    @Test fun keepsTheChosenNumberOfFramesEvenlySpread() {
        for ((src, target) in listOf(30.0 to 24, 30.0 to 25, 60.0 to 30, 60.0 to 24, 60.0 to 50, 29.97 to 25, 59.94 to 30, 50.0 to 24, 120.0 to 60)) {
            val k = kept(src, target)
            assertTrue("$src -> $target: ${k.size} frames in 10 s", k.size in target * 10 - 1..target * 10 + 1)
            val gaps = k.zipWithNext { a, b -> b - a }
            // never two kept frames closer than the source's own spacing, never a hole of 2 slots or more
            assertTrue("$src -> $target gaps ${gaps.min()}..${gaps.max()}", gaps.max() < 2_000_000L / target + 1_000_000 / src)
        }
    }

    @Test fun neverInventsOrDropsFramesAtOrBelowTheRate() {
        for ((src, target) in listOf(30.0 to 30, 30.0 to 50, 30.0 to 60, 24.0 to 25, 25.0 to 25, 29.97 to 30, 23.976 to 24)) {
            assertEquals("$src -> $target", (src * 10).toInt(), kept(src, target).size)
        }
        assertEquals(300, kept(30.0, 0).size)
    }

    @Test fun startsAgainWhenTimeGoesBack() {
        val l = FrameRateLimiter(25)
        assertTrue(l.keep(5_000_000))
        assertFalse(l.keep(5_010_000))
        assertTrue("a new clip starting at 0", l.keep(0))
        l.reset()
        assertTrue(l.keep(0))
    }
}
