package com.videoeditor.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.media.Thumbnails
import com.videoeditor.app.ui.EditorController
import com.videoeditor.core.AutoCutStrength
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Drives the real editor like a finger would and saves screenshots at each step. The project is
 * created directly (the system file picker cannot be driven from a test).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EditorUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val editor: EditorController get() = EditorController.current ?: error("editor not open")

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) { condition() }
        } catch (e: Throwable) {
            Shots.take("FAILED_$what")
            throw AssertionError("timed out waiting for: $what", e)
        }
    }

    private fun pause(ms: Long) {
        Thread.sleep(ms)
        // effects in the composition (e.g. the status message timer) run on the test clock
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
        Thread.sleep(250) // let the frame reach the screen before a screenshot
    }

    @Test fun editTimelineEndToEnd() {
        val app = Fixtures.target.applicationContext as App
        runBlocking {
            app.projects.create(listOf(Fixtures.info("speech_test.mp4"), Fixtures.info("portrait_test.mp4"), Fixtures.info("image_test.png")))
        }
        waitFor("project listed") { rule.onAllNodes(hasText("Project", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        pause(1_500)
        Shots.take("00_projects")

        rule.onAllNodes(hasText("Edited", substring = true))[0].performClick()
        waitFor("editor loaded") { EditorController.current?.loaded == true }
        val decodedAtOpen = Thumbnails.decodedCount.value
        pause(4_000)
        Shots.take("01_editor_open")
        assertEquals(3, editor.timeline.main.size)
        Report.note("ui: ${Thumbnails.decodedCount.value - decodedAtOpen} thumbnails decoded in the first 4 s after opening")

        // select the first clip (it starts at the playhead, so it is on screen)
        rule.onNodeWithTag("mainClip_0").performClick()
        pause(800)
        Shots.take("02_clip_selected")
        assertEquals(editor.timeline.main[0].id, editor.timeline.selected?.id)

        // scrub: drag the timeline to the left, the preview follows
        rule.onNodeWithTag("timeline").performTouchInput {
            swipe(Offset(centerX + 300f, centerY), Offset(centerX - 300f, centerY), durationMillis = 700)
        }
        pause(1_500)
        Shots.take("03_scrubbed")
        val afterScrub = editor.player.positionMs.value
        Report.note("ui: position after scrub $afterScrub ms")
        assertTrue("scrubbing moved the playhead ($afterScrub)", afterScrub > 1_000)

        // AutoCut with review
        rule.onNodeWithText("AutoCut").performClick()
        waitFor("autocut review", 30_000) { editor.autoCutReview != null || editor.message != null }
        pause(800)
        Shots.take("04_autocut_review")
        assertTrue("review opened, message=${editor.message}", editor.autoCutReview != null)
        rule.onNodeWithTag("autocut_${AutoCutStrength.TIGHT.name}").performClick()
        pause(400)
        Shots.take("05_autocut_tight")
        rule.onNodeWithTag("autocutApply").performClick()
        waitFor("autocut applied") { editor.timeline.main.size > 3 }
        pause(2_500)
        Shots.take("06_after_autocut")
        val pieces = editor.timeline.main.size
        Report.note("ui: main track after AutoCut has $pieces clips, ${editor.timeline.durationMs} ms")
        assertTrue("4 speech parts of the first clip + 2 other clips: $pieces", pieces >= 6)

        // undo / redo
        rule.onNodeWithContentDescription("Undo").performClick()
        pause(500)
        assertEquals(3, editor.timeline.main.size)
        rule.onNodeWithContentDescription("Redo").performClick()
        pause(800)
        assertEquals(pieces, editor.timeline.main.size)

        // the status message must not cover the timeline
        val clipTop = rule.onNodeWithTag("mainClip_0").fetchSemanticsNode().boundsInRoot.top
        rule.onAllNodesWithTag("statusMessage").fetchSemanticsNodes().forEach {
            assertTrue("status message overlaps the timeline", it.boundsInRoot.bottom < clipTop)
        }

        // two-finger zoom on the timeline (3x)
        val widthBefore = rule.onNodeWithTag("mainClip_0").fetchSemanticsNode().boundsInRoot.width
        rule.onNodeWithTag("timeline").performTouchInput {
            pinch(
                Offset(centerX - 40f, centerY), Offset(centerX - 120f, centerY),
                Offset(centerX + 40f, centerY), Offset(centerX + 120f, centerY),
                durationMillis = 500,
            )
        }
        pause(2_000)
        Shots.take("07_zoomed_in")
        val widthAfter = rule.onNodeWithTag("mainClip_0").fetchSemanticsNode().boundsInRoot.width
        Report.note("ui: pinch changed first clip width from $widthBefore px to $widthAfter px")
        assertTrue("pinch zoomed in ($widthBefore -> $widthAfter)", widthAfter > widthBefore * 2f)

        // trim the end of the first clip with its handle
        if (editor.timeline.main[0].selected.not()) rule.onNodeWithTag("mainClip_0").performClick()
        pause(600)
        assertTrue(editor.timeline.main[0].selected)
        val before = editor.timeline.main[0].durationMs
        Shots.take("08a_before_trim")
        val handle = rule.onNodeWithTag("trimEnd", useUnmergedTree = true)
        Report.note("ui: trimEnd handle bounds ${handle.fetchSemanticsNode().boundsInRoot}, clip ${rule.onNodeWithTag("mainClip_0").fetchSemanticsNode().boundsInRoot}")
        handle.performTouchInput {
            swipe(center, Offset(centerX - 90f, centerY), durationMillis = 700)
        }
        pause(1_200)
        Shots.take("08_trimmed")
        val after = editor.timeline.main[0].durationMs
        Report.note("ui: trim changed first clip from $before ms to $after ms; guide=${editor.snapGuideMs}, live=${editor.liveTrimClipId}, playhead=${editor.player.positionMs.value}")
        assertTrue("trim made the clip shorter ($before -> $after)", after < before)
        // the finger moved 90 px to the left: the edge follows it (no sticky snapping to itself)
        // (the first touch-slop pixels only start the drag, as everywhere on Android)
        val slop = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .let { android.view.ViewConfiguration.get(it).scaledTouchSlop }
        val expected = before - ((90f - slop) / (widthAfter / before)).toLong()
        assertTrue("edge followed the finger: expected about $expected ms, got $after ms", abs(after - expected) < 150)
        assertEquals("guide line cleared when the finger lifts", null, editor.snapGuideMs)
        assertEquals("live trim frame cleared", null, editor.liveTrimClipId)
        assertTrue("playhead moved to the new edge", abs(editor.player.positionMs.value - after) < 200)
        assertTrue("status message disappears on its own", rule.onAllNodesWithTag("statusMessage").fetchSemanticsNodes().isEmpty())

        // play a moment
        val playFrom = editor.player.positionMs.value
        val wallStart = android.os.SystemClock.elapsedRealtime()
        rule.onNodeWithContentDescription("Play").performClick()
        pause(2_000)
        val playing = editor.player.positionMs.value
        val wall = android.os.SystemClock.elapsedRealtime() - wallStart
        Report.note("ui: played ${playing - playFrom} ms of timeline in $wall ms of real time")
        assertTrue("playback runs at normal speed (${playing - playFrom} ms in $wall ms)", playing - playFrom <= wall + 300)
        Shots.take("09_playing")
        rule.onNodeWithContentDescription("Pause").performClick()
        pause(500)
        Report.note("ui: playing reached $playing ms")

        // a library opens and closes
        rule.onNodeWithTag("addAudio").performClick()
        pause(1_000)
        Shots.take("10_music_library")
        assertTrue("music library is open", rule.onAllNodes(hasText("Add Music")).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithContentDescription("Close").performClick()
        pause(500)

        // reopen: the project and the thumbnails come back from storage
        rule.onNodeWithContentDescription("Close project").performClick()
        pause(1_500)
        Thumbnails.clearMemory()
        rule.onAllNodes(hasText("Edited", substring = true))[0].performClick()
        waitFor("editor reloaded") { EditorController.current?.loaded == true }
        pause(2_500)
        Shots.take("11_reopened")
        assertTrue("edits were saved", editor.timeline.main.size >= 6)
        // a short clip at the default zoom still has two separate trim handles
        if (editor.timeline.main[0].selected.not()) rule.onNodeWithTag("mainClip_0").performClick()
        pause(500)
        val ls = rule.onNodeWithTag("trimStart", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val le = rule.onNodeWithTag("trimEnd", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        Report.note("ui: after reopen, handles $ls and $le")
        assertTrue("trim handles do not overlap", ls.right <= le.left)
        Shots.take("12_short_clip_handles")
        assertTrue(abs(editor.timeline.main[0].durationMs - after) < 5)
    }
}
