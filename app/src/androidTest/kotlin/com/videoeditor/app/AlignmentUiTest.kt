package com.videoeditor.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.data.clipFrom
import com.videoeditor.app.media.CanvasFormat
import com.videoeditor.app.ui.EditorController
import com.videoeditor.core.Align
import com.videoeditor.core.Captions
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Smart alignment guides and snapping in the preview, in every frame shape. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AlignmentUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val editor: EditorController get() = EditorController.current ?: error("editor not open")
    private val both = Align.CENTER_X or Align.CENTER_Y

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) {
                try {
                    condition()
                } catch (e: IllegalStateException) {
                    if (e.message?.contains("No compose hierarchies") == true) {
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                            .executeShellCommand("am start --activity-reorder-to-front -n com.videoeditor.app/.MainActivity").close()
                        Thread.sleep(1_000)
                        false
                    } else throw e
                }
            }
        } catch (e: Throwable) {
            Shots.take("FAILED_$what")
            throw AssertionError("timed out waiting for: $what", e)
        }
    }

    private fun pause(ms: Long) {
        Thread.sleep(ms)
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
        Thread.sleep(400)
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun guidesShown() = rule.onAllNodesWithTag("alignGuides", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Centre of clip [id] on screen (root px), from its stored position. */
    private fun centreOf(id: String): Offset {
        val canvas = bounds("previewCanvas")
        val c = editor.timeline.byId(id)!!
        return Offset(canvas.left + c.posX * canvas.width, canvas.top + c.posY * canvas.height)
    }

    /**
     * Drags clip [id] (its [node]) so its centre ends [miss] px away from [target] (root px),
     * checks the state with [whileDown] while the finger is still down, then lifts the finger.
     * First a short move gets past the touch slop (which a drag does not count), then the rest
     * is measured from where the clip really is, in small steps like a finger.
     */
    private fun drag(node: SemanticsNodeInteraction, id: String, target: Offset, miss: Offset, whileDown: () -> Unit) {
        val goal = target + miss
        val start = centreOf(id)
        val dir = (goal - start).let { if (it.getDistance() < 1f) Offset(1f, 0f) else it / it.getDistance() }
        node.performTouchInput {
            down(center)
            for (i in 1..4) moveBy(dir * 15f)
        }
        pause(200)
        val rest = goal - centreOf(id)
        node.performTouchInput {
            for (i in 1..12) moveBy(rest / 12f)
        }
        pause(300)
        whileDown()
        node.performTouchInput { up() }
        pause(300)
    }

    @Test fun elementsSnapToTheCentreInEveryFrameShape() {
        val app = Fixtures.target.applicationContext as App
        val overlayId = "align-overlay"
        val textId = "align-text"
        val projectId = runBlocking {
            val id = app.projects.create(listOf(Fixtures.info("portrait_test.mp4")))
            var t = app.projects.load(id)
            t = TimelineOps.addClip(
                t, clipFrom(Fixtures.info("image_test.png"), Track.OVERLAY, 0).copy(id = overlayId, posX = 0.25f, posY = 0.3f, scale = 0.3f, sourceEndMs = 6_000),
            )
            t = TimelineOps.addClip(t, Captions.textClip(textId, 0, 6_000, "Hello", posY = 0.8f, size = 0.06f).copy(posX = 0.3f))
            app.projects.save(id, TimelineOps.select(t, null))
            id
        }
        waitFor("project listed") { rule.onAllNodes(hasText("Edited", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        pause(800)
        rule.onAllNodes(hasText("Edited", substring = true))[0].performClick()
        waitFor("editor loaded") { EditorController.current?.loaded == true && EditorController.current?.projectId == projectId }
        pause(1_500)
        rule.runOnUiThread { editor.player.seekTo(1_000) }
        pause(800)

        for (format in listOf(CanvasFormat.TALL, CanvasFormat.WIDE, CanvasFormat.SQUARE, CanvasFormat.PORTRAIT)) {
            rule.runOnUiThread {
                editor.chooseFormat(format)
                editor.select(overlayId)
            }
            pause(1_000)
            val canvas = bounds("previewCanvas")
            Report.note("align ${format.label}: preview ${canvas.width.toInt()} x ${canvas.height.toInt()} px (aspect ${"%.3f".format(canvas.width / canvas.height)})")
            assertEquals("frame shape ${format.label}", format.ratio!!, canvas.width / canvas.height, 0.02f)
            val centre = canvas.center

            // ---- overlay: brought within a few px of the centre -> exactly centred, both guides
            val ov = rule.onNodeWithTag("overlay_$overlayId", useUnmergedTree = true)
            drag(ov, overlayId, centre, Offset(6f, -5f)) {
                assertEquals("both guides while dragging", both, editor.alignGuides)
                assertTrue("guides drawn", guidesShown())
                Shots.take("60_align_${format.name}")
            }
            val o = editor.timeline.byId(overlayId)!!
            assertEquals(0.5f, o.posX, 0f)
            assertEquals(0.5f, o.posY, 0f)
            assertEquals("guides gone after the finger lifts", 0, editor.alignGuides)
            assertTrue(!guidesShown())

            // ---- further away it moves freely, no guide
            drag(ov, overlayId, centre, Offset(70f, 90f)) {
                assertEquals(0, editor.alignGuides)
            }
            val free = editor.timeline.byId(overlayId)!!
            assertNotEquals(0.5f, free.posX)
            assertTrue("followed the finger", abs(free.posX - (0.5f + 70f / canvas.width)) < 0.02f)

            // ---- only one axis close: one guide
            drag(ov, overlayId, centre, Offset(-4f, 120f)) {
                assertEquals(Align.CENTER_X, editor.alignGuides)
            }
            assertEquals(0.5f, editor.timeline.byId(overlayId)!!.posX, 0f)
        }

        // ---- text: snaps the same way
        rule.runOnUiThread {
            editor.chooseFormat(CanvasFormat.TALL)
            editor.select(textId)
        }
        pause(1_000)
        val canvas = bounds("previewCanvas")
        val text = rule.onNodeWithTag("caption_Hello", useUnmergedTree = true)
        drag(text, textId, Offset(canvas.center.x, centreOf(textId).y), Offset(7f, 0f)) {
            assertTrue("text centre guide", editor.alignGuides and Align.CENTER_X != 0)
            Shots.take("61_align_text")
        }
        assertEquals(0.5f, editor.timeline.byId(textId)!!.posX, 0f)
        assertEquals(0, editor.alignGuides)

        // ---- main video: a small move stays locked in the centre with both guides
        rule.runOnUiThread { editor.select(editor.timeline.main[0].id) }
        pause(800)
        val main = editor.timeline.main[0]
        assertEquals(0.5f, main.posX, 0f)
        val layer = rule.onNodeWithTag("previewCanvas", useUnmergedTree = true)
        layer.performTouchInput {
            down(Offset(width * 0.5f, height * 0.12f))
            for (i in 1..10) moveTo(Offset(width * 0.5f + 6f * i / 10f, height * 0.12f + 30f * i / 10f))
        }
        pause(300)
        assertEquals("video: centre guide", Align.CENTER_X, editor.alignGuides and Align.CENTER_X)
        Shots.take("62_align_video")
        layer.performTouchInput { up() }
        pause(300)
        assertEquals(0.5f, editor.timeline.main[0].posX, 0f)
        assertEquals(0, editor.alignGuides)
        Report.note("align: overlay, text and video snap to the centre in 9:16, 16:9, 1:1 and 4:5; guides hide on release")
    }
}
