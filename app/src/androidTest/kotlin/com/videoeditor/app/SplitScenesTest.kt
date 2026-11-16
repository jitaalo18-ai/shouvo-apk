package com.videoeditor.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.media.SceneSplitter
import com.videoeditor.app.ui.EditorController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * "Split scenes" (like CapCut's) on the device. scenes_test.mp4 is 7 s with four shots: a moving
 * test picture (0 - 2 s), a zooming fractal (2 - 3.5 s), a panning picture (3.5 - 5.5 s) and
 * noisy colour bars (5.5 - 7 s). The cuts are found from the decoded video, the clip is split
 * exactly there, and a video that is one shot is left alone.
 */
@RunWith(AndroidJUnit4::class)
class SplitScenesTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ctx get() = Fixtures.target
    private val editor: EditorController get() = EditorController.current ?: error("editor not open")
    private val expected = listOf(2_000L, 3_500L, 5_500L)

    private fun near(found: List<Long>, want: List<Long>) =
        found.size == want.size && found.zip(want).all { (a, b) -> abs(a - b) <= 40 }

    @Test fun findsTheCutsInTheDecodedVideo() = runBlocking {
        val uri = Fixtures.uri("scenes_test.mp4")
        var last = 0f
        val t0 = System.currentTimeMillis()
        val cuts = SceneSplitter.findCuts(ctx, uri, 0, 7_000) { p ->
            assertTrue("progress goes forward", p >= last)
            last = p
        }
        Report.note("split scenes: 7 s video, ${SceneSplitter.lastFrames} frames looked at in ${System.currentTimeMillis() - t0} ms, cuts at $cuts ms (made at $expected)")
        assertTrue("cuts $cuts", near(cuts, expected))
        assertEquals(1f, last)
        assertTrue("every frame was looked at (${SceneSplitter.lastFrames})", SceneSplitter.lastFrames in 205..211)
        // only a part of the file (a trimmed clip): only the cuts inside it, in file time
        val part = SceneSplitter.findCuts(ctx, uri, 3_000, 6_500) { }
        assertTrue("part: $part", near(part, listOf(3_500L, 5_500L)))
        // one shot with a lot of motion (20 s moving test picture): nothing to split
        val t1 = System.currentTimeMillis()
        val none = SceneSplitter.findCuts(ctx, Fixtures.uri("phone_test.mp4"), 0, 20_000) { }
        Report.note("split scenes: 20 s single-shot 720x1280 video, ${SceneSplitter.lastFrames} frames in ${System.currentTimeMillis() - t1} ms, cuts $none")
        assertEquals(emptyList<Long>(), none)
    }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) { condition() }
        } catch (e: Throwable) {
            Shots.take("FAILED_$what")
            throw AssertionError("timed out waiting for: $what", e)
        }
    }

    private fun pause(ms: Long) {
        Thread.sleep(ms)
        rule.waitForIdle()
    }

    @Test fun splitScenesButtonCutsTheClipIntoItsShots() {
        val app = ctx.applicationContext as App
        val projectId = runBlocking { app.projects.create(listOf(Fixtures.info("scenes_test.mp4"), Fixtures.info("portrait_test.mp4"))) }
        waitFor("project listed") { rule.onAllNodes(hasText("Edited", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        pause(800)
        rule.onAllNodes(hasText("Edited", substring = true))[0].performClick()
        waitFor("editor loaded") { EditorController.current?.loaded == true && EditorController.current?.projectId == projectId }
        pause(1_000)
        val first = editor.timeline.main[0]
        val second = editor.timeline.main[1]
        val total = editor.timeline.durationMs
        rule.runOnUiThread { editor.select(first.id) }
        pause(600)
        Shots.take("95_split_scenes_before")

        rule.onNodeWithText("Split scenes").performScrollTo().performClick()
        waitFor("scenes split", 60_000) { editor.timeline.main.size == 5 && editor.busy == null }
        pause(600)
        assertTrue(rule.onAllNodes(hasText("Split into 4 scenes")).fetchSemanticsNodes().isNotEmpty())
        Shots.take("96_split_scenes_after")
        val main = editor.timeline.main
        val starts = main.map { it.timelineStartMs }
        Report.note("split scenes button: pieces start at $starts ms")
        assertTrue("pieces start at the cuts: $starts", near(starts.subList(1, 4), expected))
        assertEquals(0L, starts[0])
        assertEquals("the next clip did not move", second.timelineStartMs, starts[4])
        assertEquals(second.id, main[4].id)
        assertEquals("nothing lost", total, editor.timeline.durationMs)
        for (i in 0 until 4) {
            assertEquals("same file", first.sourceUri, main[i].sourceUri)
            assertEquals("picture and sound continue where the piece before ended", main[i].timelineStartMs, main[i].sourceStartMs)
        }
        assertEquals(first.sourceEndMs, main[3].sourceEndMs)

        // one undo step; saved
        rule.runOnUiThread { editor.undo() }
        pause(500)
        assertEquals(2, editor.timeline.main.size)
        rule.runOnUiThread { editor.redo() }
        pause(800)
        assertEquals(5, editor.timeline.main.size)
        assertEquals(5, runBlocking { app.projects.load(projectId) }.main.size)

        // a piece that is one shot: told, nothing changes
        rule.runOnUiThread { editor.select(editor.timeline.main[0].id) }
        pause(500)
        rule.onNodeWithText("Split scenes").performScrollTo().performClick()
        waitFor("answer") { rule.onAllNodes(hasText("No scene changes were found in this clip.")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(5, editor.timeline.main.size)
        assertTrue(rule.onAllNodes(hasTestTag("splitScenesCancel")).fetchSemanticsNodes().isEmpty())
        assertEquals("no player errors (${editor.player.lastError})", 0, editor.player.errors)
    }
}
