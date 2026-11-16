package com.videoeditor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SplitScreenTests {
    private fun clip(id: String, track: Track, aspect: Float, start: Long = 0, dur: Long = 5000, layer: Int = 0, kind: MediaKind = MediaKind.VIDEO) = Clip(
        id = id, track = track, kind = kind, sourceUri = "file:///$id", sourceDurationMs = dur, sourceStartMs = 0, sourceEndMs = dur,
        timelineStartMs = start, hasAudio = false, aspect = aspect, layer = layer,
        scale = if (track == Track.MAIN) TimelineOps.MAIN_SCALE_UNIT else 0.4f,
    )

    private val canvases = listOf(9f / 16f, 16f / 9f, 1f, 4f / 5f)
    private val aspects = listOf(9f / 16f, 16f / 9f, 1f, 0.3f, 3.2f)

    @Test fun everyLayoutCoversTheFrameWithoutOverlap() {
        for (n in 2..SplitScreen.MAX_CLIPS) {
            val layouts = SplitScreen.layouts(n)
            assertTrue("layouts for $n", layouts.size >= 2)
            assertEquals(layouts.size, layouts.map { it.id }.toSet().size)
            for (l in layouts) {
                assertEquals("${l.id} for $n", n, l.cells.size)
                assertEquals("area of ${l.id} for $n", 1.0, l.cells.sumOf { (it.w * it.h).toDouble() }, 1e-4)
                for (c in l.cells) assertTrue(c.x >= -1e-6 && c.y >= -1e-6 && c.x + c.w <= 1 + 1e-5 && c.y + c.h <= 1 + 1e-5)
                for (i in l.cells.indices) for (j in i + 1 until l.cells.size) {
                    val a = l.cells[i]; val b = l.cells[j]
                    val ox = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
                    val oy = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
                    assertFalse("${l.id} for $n: parts $i and $j overlap", ox > 1e-4 && oy > 1e-4)
                }
            }
        }
        assertTrue(SplitScreen.layouts(1).isEmpty())
    }

    @Test fun everyPictureFillsItsPartExactlyAndIsNotSqueezed() {
        for (canvas in canvases) for (n in 2..6) for (layout in SplitScreen.layouts(n)) {
            val clips = List(n) { i -> clip("c$i", if (i == 0) Track.MAIN else Track.OVERLAY, aspects[(i + n) % aspects.size], layer = i) }
            val out = SplitScreen.apply(Timeline(clips), clips.map { it.id }, layout, canvas)
            for ((i, cell) in layout.cells.withIndex()) {
                val c = out.byId("c$i")!!
                val box = SplitScreen.shownBox(c, canvas)
                val what = "canvas $canvas, ${layout.id} of $n, picture $i"
                assertEquals(what, cell.x, box.x, 1e-3f); assertEquals(what, cell.y, box.y, 1e-3f)
                assertEquals(what, cell.w, box.w, 1e-3f); assertEquals(what, cell.h, box.h, 1e-3f)
                assertTrue("$what: cropped on one side only", c.cropX == 1f || c.cropY == 1f)
                assertTrue(c.cropX in SplitScreen.MIN_CROP..1f && c.cropY in SplitScreen.MIN_CROP..1f)
                assertEquals(1f, c.stretchX); assertEquals(1f, c.stretchY); assertEquals(0f, c.rotation)
                // the shown part keeps the picture's own proportions: (aspect x cropX / cropY) is the part's shape
                val shownAspect = c.aspect * c.cropX / c.cropY
                assertTrue(what, abs(shownAspect / (cell.w * canvas / cell.h) - 1f) < 1e-3f)
            }
        }
    }

    @Test fun picksThePicturesUnderThePlayheadMainFirst() {
        val t = Timeline(listOf(
            clip("m1", Track.MAIN, 1f, 0), clip("m2", Track.MAIN, 1f, 5000),
            clip("o2", Track.OVERLAY, 1f, 1000, layer = 2), clip("o1", Track.OVERLAY, 1f, 0, layer = 1),
            clip("late", Track.OVERLAY, 1f, 6000), clip("txt", Track.TEXT, 1f, 0, kind = MediaKind.TEXT),
            clip("snd", Track.SFX, 1f, 0, kind = MediaKind.AUDIO),
        ))
        assertEquals(listOf("m1", "o1", "o2"), SplitScreen.clipsAt(t, 2000).map { it.id })
        assertEquals(listOf("m1", "o1"), SplitScreen.clipsAt(t, 500).map { it.id })
        assertEquals(listOf("m2", "late"), SplitScreen.clipsAt(t, 7000).map { it.id })
    }

    @Test fun resetGivesWholePicturesBackAndOthersAreUntouched() {
        val clips = listOf(clip("m", Track.MAIN, 16f / 9f), clip("o", Track.OVERLAY, 9f / 16f), clip("other", Track.OVERLAY, 1f).copy(posX = 0.2f))
        val t = Timeline(clips)
        val split = SplitScreen.apply(t, listOf("m", "o"), SplitScreen.layouts(2)[0], 9f / 16f)
        assertTrue(split.byId("m")!!.isCropped)
        assertEquals(clips[2], split.byId("other"))
        val back = SplitScreen.reset(split, listOf("m", "o"))
        assertEquals(clips[0], back.byId("m"))
        assertFalse(back.byId("o")!!.isCropped)
        assertEquals(1f, TimelineOps.mainZoom(back.byId("m")!!))
    }

    @Test fun croppedClipsAreNotStreamCopied() {
        val c = clip("m", Track.MAIN, 9f / 16f).copy(hasAudio = true, audio = AudioFx(isolate = true), sourceDurationMs = 5000)
        assertTrue(ExportPolicy.canCopyVideo(Timeline(listOf(c)), 9f / 16f))
        assertFalse(ExportPolicy.canCopyVideo(Timeline(listOf(c.copy(cropY = 0.5f))), 9f / 16f))
    }
}
