package com.videoeditor.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.ui.EditorController
import com.videoeditor.core.Compound
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compound clips, copy / paste / duplicate and project delete, driven through the real UI. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CompoundUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val editor: EditorController get() = EditorController.current ?: error("editor not open")

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) {
                try {
                    condition()
                } catch (e: IllegalStateException) {
                    // The emulator sometimes opens a system dialog (e.g. SIM settings) on top of the
                    // app at start-up; then no app window is found. Bring the app back to the front.
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
        Thread.sleep(600) // let the new frame reach the screen before a screenshot
    }

    /** Taps a bottom-bar button (scrolling the bar to it first). */
    private fun tool(label: String) {
        rule.onNodeWithText(label).performScrollTo().performClick()
        pause(700)
    }

    private fun seek(ms: Long) {
        rule.runOnUiThread { editor.player.seekTo(ms) }
        pause(600)
    }

    @Test fun compoundCopyDuplicateAndDeleteProject() {
        val app = Fixtures.target.applicationContext as App
        val projectId = runBlocking {
            app.projects.create(
                listOf(
                    Fixtures.info("speech_test.mp4"), Fixtures.info("portrait_test.mp4"),
                    Fixtures.info("image_test.png"), Fixtures.info("portrait_test.mp4"),
                ),
            )
        }
        waitFor("project listed") { rule.onAllNodes(hasText("Edited", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        pause(1_000)
        rule.onAllNodes(hasText("Edited", substring = true))[0].performClick()
        waitFor("editor loaded") { EditorController.current?.loaded == true && EditorController.current?.projectId == projectId }
        pause(2_000)
        val original = editor.timeline.main
        assertEquals(4, original.size)
        val total = editor.timeline.durationMs

        // ---- Compound: nothing selected -> Compound -> All -> Compound
        tool("Compound")
        assertNotNull("picking mode", editor.compoundPick)
        rule.onNodeWithTag("compoundAll").performClick()
        pause(600)
        Shots.take("20_compound_pick")
        assertEquals(0..3, editor.compoundRange)
        rule.onNodeWithTag("compoundMake").performClick()
        pause(1_200)
        Shots.take("21_compound_made")
        val compound = editor.timeline.main.single()
        assertTrue(compound.isCompound)
        assertEquals(4, compound.children.size)
        assertEquals("same length as before", total, editor.timeline.durationMs)
        assertEquals(
            "plays the same pieces",
            original.map { listOf(it.sourceUri, it.sourceStartMs, it.sourceEndMs, it.timelineStartMs) },
            editor.flat.main.map { listOf(it.sourceUri, it.sourceStartMs, it.sourceEndMs, it.timelineStartMs) },
        )
        Report.note("ui: compound made from 4 clips, ${compound.durationMs} ms")

        // ---- it plays across the clips inside it
        seek(23_500)
        rule.onNodeWithContentDescription("Play").performClick()
        pause(2_000)
        val playing = editor.player.positionMs.value
        rule.onNodeWithContentDescription("Pause").performClick()
        pause(500)
        Shots.take("22_compound_playing")
        Report.note("ui: playing inside the compound went from 23500 to $playing ms")
        assertTrue("playback crossed into the second clip ($playing)", playing > 24_300)

        // ---- picking only two ticks the ones in between too
        rule.onNodeWithContentDescription("Undo").performClick()
        pause(800)
        assertEquals(4, editor.timeline.main.size)
        tool("Compound")
        rule.onNodeWithTag("mainClip_1").performClick()
        pause(300)
        rule.onNodeWithTag("mainClip_3").performClick()
        pause(600)
        Shots.take("23_compound_pick_two")
        assertEquals(1..3, editor.compoundRange)
        rule.onNodeWithTag("compoundMake").performClick()
        pause(1_000)
        assertEquals(2, editor.timeline.main.size)
        assertEquals(3, editor.timeline.main[1].children.size)
        assertEquals(total, editor.timeline.durationMs)

        // ---- split the compound at the playhead, then take it apart
        val inner = editor.timeline.main[1]
        if (editor.timeline.selected?.id != inner.id) rule.onNodeWithTag("mainClip_1").performClick()
        seek(inner.timelineStartMs + 3_000)
        tool("Split")
        assertEquals(3, editor.timeline.main.size)
        assertTrue(editor.timeline.main[1].isCompound && editor.timeline.main[2].isCompound)
        assertEquals(total, editor.timeline.durationMs)
        Shots.take("24_compound_split")
        rule.onNodeWithContentDescription("Undo").performClick()
        pause(800)
        if (editor.timeline.selected?.id != inner.id) rule.onNodeWithTag("mainClip_1").performClick()
        pause(400)
        tool("Uncompound")
        assertEquals(4, editor.timeline.main.size)
        assertTrue(editor.timeline.main.none { it.isCompound })
        assertEquals(original.map { it.durationMs }, editor.timeline.main.map { it.durationMs })
        Shots.take("25_uncompound")
        rule.onNodeWithContentDescription("Undo").performClick()
        pause(800)
        assertEquals(2, editor.timeline.main.size)

        // ---- Copy, then Paste at the end; Duplicate
        if (editor.timeline.selected?.id != editor.timeline.main[1].id) rule.onNodeWithTag("mainClip_1").performClick()
        pause(400)
        tool("Copy")
        assertNotNull(EditorController.clipboard)
        rule.onNodeWithContentDescription("Back").performClick()
        pause(500)
        seek(editor.timeline.durationMs)
        tool("Paste")
        assertEquals(3, editor.timeline.main.size)
        assertTrue("pasted compound", editor.timeline.main[2].isCompound)
        assertEquals(total + inner.durationMs, editor.timeline.durationMs)
        Shots.take("26_pasted")
        val ids = editor.timeline.main.map { it.id }
        assertEquals("pasted clip has its own id", ids.size, ids.toSet().size)
        if (editor.timeline.selected?.id != editor.timeline.main[0].id) rule.onNodeWithTag("mainClip_0").performClick()
        pause(400)
        tool("Duplicate")
        assertEquals(4, editor.timeline.main.size)
        assertEquals(editor.timeline.main[0].sourceUri, editor.timeline.main[1].sourceUri)
        assertEquals(editor.timeline.main[0].durationMs, editor.timeline.main[1].durationMs)
        assertEquals(editor.timeline.main[1].id, editor.timeline.selected?.id)
        Shots.take("27_duplicated")
        val before = editor.timeline.main

        // ---- close and reopen: compounds come back with the clips inside them
        rule.onNodeWithContentDescription("Close project").performClick()
        pause(1_500)
        Shots.take("28_projects_after")
        rule.onAllNodes(hasText("Edited", substring = true))[0].performClick()
        waitFor("editor reloaded") { EditorController.current?.loaded == true && EditorController.current?.projectId == projectId }
        pause(1_500)
        assertEquals(before.map { it.copy(selected = false) }, editor.timeline.main.map { it.copy(selected = false) })
        assertEquals(3, Compound.leafCount(editor.timeline.main[2]))
        Shots.take("29_reopened")

        // ---- delete the project from the list
        rule.onNodeWithContentDescription("Close project").performClick()
        pause(1_500)
        rule.onNodeWithTag("projectMenu_$projectId").performClick()
        pause(500)
        rule.onNodeWithTag("projectDelete").performClick()
        pause(500)
        Shots.take("30_delete_confirm")
        rule.onNodeWithTag("confirmDelete").performClick()
        waitFor("project deleted") { runBlocking { app.projects.get(projectId) } == null }
        pause(1_000)
        Shots.take("31_deleted")
        assertTrue(rule.onAllNodesWithTag("projectMenu_$projectId").fetchSemanticsNodes().isEmpty())
        assertTrue(runBlocking { app.projects.load(projectId) }.clips.isEmpty())
        assertNull(runBlocking { app.projects.get(projectId) })
    }
}
