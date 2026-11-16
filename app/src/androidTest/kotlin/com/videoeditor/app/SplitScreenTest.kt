package com.videoeditor.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.data.clipFrom
import com.videoeditor.app.media.Exporter
import com.videoeditor.app.media.MediaProbe
import com.videoeditor.app.ui.EditorController
import com.videoeditor.core.SplitScreen
import com.videoeditor.core.Timeline
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Split screen: the pictures at the playhead each get their own part of the frame, filled
 * completely and not squeezed - in the exported video (measured) and in the editor (button,
 * layouts, Reset, undo, saved).
 */
@RunWith(AndroidJUnit4::class)
class SplitScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()
    private val ctx get() = Fixtures.target
    private val editor: EditorController get() = EditorController.current ?: error("editor not open")

    /** A 400 x 400 picture: [bg] with a 100 x 100 [mark] square in the middle. */
    private fun picture(name: String, bg: Int, mark: Int): File {
        val f = File(ctx.cacheDir, "$name.png")
        val b = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(bg)
            drawRect(150f, 150f, 250f, 250f, Paint().apply { color = mark })
        }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun near(c: Int, want: Int) =
        abs(Color.red(c) - Color.red(want)) + abs(Color.green(c) - Color.green(want)) + abs(Color.blue(c) - Color.blue(want)) < 110

    /** Bounding box of the pixels near [want] inside the given part of [frame]: left, top, right, bottom. */
    private fun box(frame: Bitmap, want: Int, x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (y in y0 until y1) for (x in x0 until x1) if (near(frame.getPixel(x, y), want)) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        return intArrayOf(l, t, r, b)
    }

    private fun share(frame: Bitmap, want: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var n = 0
        var all = 0
        for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) {
            all++
            if (near(frame.getPixel(x, y), want)) n++
        }
        return n.toFloat() / all
    }

    private fun frame(uri: Uri): Bitmap {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            return r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            r.release()
        }
    }

    private val blue = Color.rgb(20, 40, 220)
    private val yellow = Color.rgb(250, 230, 20)
    private val red = Color.rgb(220, 30, 30)
    private val green = Color.rgb(20, 200, 60)

    @Test fun exportedSplitScreenFillsEachPartWithoutSqueezing() = runBlocking {
        val a = picture("split_a", blue, yellow)
        val b = picture("split_b", red, green)
        val main = clipFrom(MediaProbe.probe(ctx, Uri.fromFile(a))!!, Track.MAIN)
        val over = clipFrom(MediaProbe.probe(ctx, Uri.fromFile(b))!!, Track.OVERLAY, 0)
        val base = TimelineOps.addClip(TimelineOps.appendMain(Timeline(), listOf(main)), over)
        val canvas = 9f / 16f
        val ids = SplitScreen.clipsAt(base, 500).map { it.id }
        assertEquals(listOf(main.id, over.id), ids)
        for (layout in SplitScreen.layouts(2)) {
            val out = Exporter(ctx).export(SplitScreen.apply(base, ids, layout, canvas), canvas) { }
            try {
                val f = frame(out)
                val w = f.width
                val h = f.height
                assertTrue("portrait frame ${w}x$h", h > w)
                for ((i, cell) in layout.cells.withIndex()) {
                    val x0 = (cell.x * w).toInt(); val y0 = (cell.y * h).toInt()
                    val x1 = ((cell.x + cell.w) * w).toInt(); val y1 = ((cell.y + cell.h) * h).toInt()
                    val bg = if (i == 0) blue else red
                    val mark = if (i == 0) yellow else green
                    val other = if (i == 0) red else blue
                    // the part is full of this picture (its background + its square), nothing of the other one, no black bars
                    val own = share(f, bg, x0 + 6, y0 + 6, x1 - 6, y1 - 6) + share(f, mark, x0 + 6, y0 + 6, x1 - 6, y1 - 6)
                    val foreign = share(f, other, x0 + 6, y0 + 6, x1 - 6, y1 - 6)
                    // the square: in the middle of the part, still a square, at the cover scale
                    val m = box(f, mark, x0, y0, x1, y1)
                    val mw = m[2] - m[0] + 1
                    val mh = m[3] - m[1] + 1
                    val scale = maxOf((x1 - x0) / 400f, (y1 - y0) / 400f)
                    Report.note("split export ${layout.id} part $i: filled %.0f%%, square %dx%d at (%d,%d), expected %.0f px in the middle of %d..%d x %d..%d".format(
                        own * 100, mw, mh, (m[0] + m[2]) / 2, (m[1] + m[3]) / 2, 100 * scale, x0, x1, y0, y1))
                    assertTrue("${layout.id} part $i is filled by its picture (${own * 100}%)", own > 0.97f)
                    assertTrue("${layout.id} part $i shows nothing of the other picture", foreign < 0.01f)
                    assertTrue("${layout.id} part $i: the square is still a square ($mw x $mh)", abs(mw - mh) <= maxOf(6, mw / 20))
                    assertTrue("${layout.id} part $i: scaled to cover ($mw vs ${100 * scale})", abs(mw - 100 * scale) <= 100 * scale * 0.06f + 4)
                    assertTrue("${layout.id} part $i: centred", abs((m[0] + m[2]) / 2 - (x0 + x1) / 2) <= 8 && abs((m[1] + m[3]) / 2 - (y0 + y1) / 2) <= 8)
                }
            } finally {
                ctx.contentResolver.delete(out, null, null)
            }
        }
        a.delete(); b.delete()
        Unit
    }

    private fun pause(ms: Long) {
        Thread.sleep(ms)
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
        Thread.sleep(500)
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) { condition() }
        } catch (e: Throwable) {
            Shots.take("FAILED_$what")
            throw AssertionError("timed out waiting for: $what", e)
        }
    }

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun splitScreenButtonArrangesTheClipsAtThePlayhead() {
        val app = ctx.applicationContext as App
        val pic = picture("split_ui", red, green)
        val projectId = runBlocking {
            val id = app.projects.create(listOf(Fixtures.info("portrait_test.mp4")))
            var t = app.projects.load(id)
            t = TimelineOps.addClip(t, clipFrom(MediaProbe.probe(ctx, Uri.fromFile(pic))!!, Track.OVERLAY, 0))
            t = TimelineOps.addClip(t, clipFrom(Fixtures.info("image_test.png"), Track.OVERLAY, 0))
            app.projects.save(id, TimelineOps.select(t, null))
            id
        }
        waitFor("project listed") { rule.onAllNodes(hasText("Edited", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        pause(800)
        TestProjects.openNewest(rule)
        waitFor("editor loaded") { EditorController.current?.loaded == true && EditorController.current?.projectId == projectId }
        pause(1_500)
        rule.runOnUiThread { editor.player.seekTo(1_000) }
        pause(800)
        val before = editor.timeline
        assertEquals(3, editor.splitClips.size)
        Shots.take("90_split_before")

        rule.onNodeWithText("Split screen").performScrollTo().performClick()
        waitFor("split screen panel") { shown("splitScreenSheet") }
        val aspect = editor.canvasAspect
        for (layout in SplitScreen.layouts(3)) {
            rule.onNodeWithTag("splitLayout_${layout.id}").performScrollTo().performClick()
            pause(700)
            val clips = editor.splitClips
            for ((c, cell) in clips.zip(layout.cells)) {
                val box = SplitScreen.shownBox(c, aspect)
                assertTrue("${layout.id}: ${c.track} in its part", abs(box.x - cell.x) < 0.003f && abs(box.y - cell.y) < 0.003f &&
                    abs(box.w - cell.w) < 0.003f && abs(box.h - cell.h) < 0.003f)
            }
            Shots.take("91_split_${layout.id}")
        }
        // Reset: whole pictures again
        rule.onNodeWithTag("splitReset").performClick()
        pause(600)
        assertTrue(editor.splitClips.none { it.isCropped })
        // a layout again, close, undo / redo, saved
        rule.onNodeWithTag("splitLayout_rows").performScrollTo().performClick()
        pause(600)
        rule.onNodeWithTag("splitDone").performClick()
        pause(600)
        assertFalse(shown("splitScreenSheet"))
        val split = editor.timeline
        assertTrue(split.clips.any { it.isCropped })
        waitFor("saved") { runBlocking { app.projects.load(projectId) }.clips.map { it.cropX to it.cropY } == split.clips.map { it.cropX to it.cropY } }
        rule.runOnUiThread { editor.undo() }
        pause(500)
        assertTrue("undo takes one layout step back", editor.timeline.clips.none { it.isCropped })
        rule.runOnUiThread { editor.redo() }
        pause(500)
        assertEquals(split.clips.map { it.cropX }, editor.timeline.clips.map { it.cropX })
        // playing with the split screen: no player error
        rule.runOnUiThread { editor.player.play() }
        Thread.sleep(1_500)
        rule.runOnUiThread { editor.player.pause() }
        assertEquals("no player errors (${editor.player.lastError})", 0, editor.player.errors)
        assertEquals(before.clips.size, editor.timeline.clips.size)
        pic.delete()
    }

    @Test fun oneClipAloneGetsAHint() {
        val app = ctx.applicationContext as App
        val projectId = runBlocking { app.projects.create(listOf(Fixtures.info("portrait_test.mp4"))) }
        waitFor("project listed") { rule.onAllNodes(hasText("Edited", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        pause(800)
        TestProjects.openNewest(rule)
        waitFor("editor loaded") { EditorController.current?.loaded == true && EditorController.current?.projectId == projectId }
        pause(1_000)
        rule.onNodeWithText("Split screen").performScrollTo().performClick()
        // the hint is shown (and no panel)
        waitFor("hint", 5_000) {
            editor.message?.contains("two or more") == true ||
                rule.onAllNodes(hasText("two or more", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(shown("splitScreenSheet"))
    }
}
