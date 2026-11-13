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
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import com.videoeditor.app.data.clipFrom
import com.videoeditor.core.MediaKind
import com.videoeditor.core.Timeline
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
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
        assertNotNull("picking mode", editor.multiPick)
        rule.onNodeWithTag("pickAll").performClick()
        pause(600)
        Shots.take("20_compound_pick")
        assertEquals(0..3, editor.pickRange)
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
        assertEquals(1..3, editor.pickRange)
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

    /** What plays where, sampled every 250 ms per track (so a cut-up clip equals the whole clip). */
    private fun plays(t: Timeline): List<String> =
        Compound.flatten(t, editor.canvasAspect).clips.flatMap { c ->
            (c.timelineStartMs until c.timelineEndMs step 250L).map { ms ->
                val frame = if (c.track == Track.OVERLAY) String.format("%.3f/%.3f/%.3f", c.posX, c.posY, c.scale) else ""
                "${c.track}|${c.sourceUri}|$ms|${c.timelineToSource(ms)}|$frame"
            }
        }.sorted()

    @Test fun multiTrackCompoundByLongPress() {
        val app = Fixtures.target.applicationContext as App
        val projectId = runBlocking {
            val id = app.projects.create(listOf(Fixtures.info("speech_test.mp4"), Fixtures.info("portrait_test.mp4")))
            var t = app.projects.load(id)
            val first = t.main[0]
            t = TimelineOps.trimMain(t, first.id, newSourceEndMs = 8_000)
            val image = Fixtures.info("image_test.png")
            val speech = Fixtures.info("speech_test.mp4")
            t = TimelineOps.addClip(t, clipFrom(image, Track.OVERLAY, 1_000).copy(posX = 0.3f, posY = 0.3f, scale = 0.3f))
            t = TimelineOps.addClip(t, clipFrom(image, Track.OVERLAY, 9_000).copy(posX = 0.7f, posY = 0.7f, scale = 0.25f))
            t = TimelineOps.addClip(t, clipFrom(speech, Track.SFX, 2_000).copy(kind = MediaKind.AUDIO, sourceEndMs = 3_000, name = "sfx"))
            t = TimelineOps.addClip(t, clipFrom(speech, Track.MUSIC, 0).copy(kind = MediaKind.AUDIO, sourceEndMs = 20_000, name = "music"))
            app.projects.save(id, TimelineOps.select(t, null))
            id
        }
        waitFor("project listed") { rule.onAllNodes(hasText("Edited", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        pause(1_000)
        rule.onAllNodes(hasText("Edited", substring = true))[0].performClick()
        waitFor("editor loaded") { EditorController.current?.loaded == true && EditorController.current?.projectId == projectId }
        pause(2_000)
        Shots.take("40_multi_start")
        val original = editor.timeline
        val before = plays(original)
        assertEquals(2, original.main.size)
        assertEquals(2, original.track(Track.OVERLAY).size)

        // ---- long-press the first video clip: multi-select starts with it
        rule.onNodeWithTag("mainClip_0").performTouchInput { longClick() }
        pause(600)
        assertEquals(setOf(original.main[0].id), editor.multiPick)
        // hold the first overlay, then tap the sound, the music and the second video clip
        rule.onNodeWithTag("chip_OVERLAY_0").performTouchInput { longClick() }
        pause(400)
        rule.onNodeWithTag("chip_SFX_0").performClick()
        pause(300)
        rule.onNodeWithTag("chip_MUSIC_0").performClick()
        pause(300)
        rule.onNodeWithTag("mainClip_1").performClick()
        pause(600)
        Shots.take("41_multi_picked")
        assertEquals(5, editor.multiPick?.size)
        assertEquals(5, editor.pickCount)
        // tapping a picked clip again takes it out, tapping again puts it back
        rule.onNodeWithTag("chip_SFX_0").performClick()
        pause(300)
        assertEquals(4, editor.multiPick?.size)
        rule.onNodeWithTag("chip_SFX_0").performClick()
        pause(300)

        rule.onNodeWithTag("compoundMake").performClick()
        pause(1_200)
        Shots.take("42_multi_compound")
        val compound = editor.timeline.main.single()
        assertTrue(compound.isCompound)
        assertEquals(
            mapOf(Track.MAIN to 2, Track.OVERLAY to 1, Track.SFX to 1, Track.MUSIC to 1),
            Compound.leafTracks(compound),
        )
        assertEquals("the second overlay was not picked", 1, editor.timeline.track(Track.OVERLAY).size)
        // the music ran past the end of the video: that part stays on the music track
        assertEquals(compound.timelineEndMs, editor.timeline.track(Track.MUSIC).single().timelineStartMs)
        assertEquals("plays exactly as before", before, plays(editor.timeline))
        Report.note("ui: multi-track compound ${Compound.leafTracks(compound)}")

        // ---- it plays (video, overlay pieces and sound pieces)
        seek(1_500)
        rule.onNodeWithContentDescription("Play").performClick()
        pause(1_500)
        Shots.take("43_multi_playing")
        rule.onNodeWithContentDescription("Pause").performClick()
        pause(400)
        assertTrue(editor.player.positionMs.value > 1_800)

        // ---- undo / redo the compound
        rule.onNodeWithContentDescription("Undo").performClick()
        pause(800)
        assertEquals(2, editor.timeline.main.size)
        assertTrue(editor.timeline.clips.none { it.isCompound })
        assertEquals(before, plays(editor.timeline))
        rule.onNodeWithContentDescription("Redo").performClick()
        pause(800)
        assertTrue(editor.timeline.main.single().isCompound)

        // ---- add the second overlay to the compound later: hold the compound, tap the overlay
        rule.onNodeWithTag("mainClip_0").performTouchInput { longClick() }
        pause(500)
        rule.onNodeWithTag("chip_OVERLAY_0").performClick()
        pause(500)
        rule.onNodeWithTag("compoundMake").performClick()
        pause(1_200)
        Shots.take("44_added_to_compound")
        val grown = editor.timeline.main.single()
        assertTrue(grown.isCompound)
        assertEquals("one compound, not one inside another", 6, grown.children.size)
        assertTrue(editor.timeline.track(Track.OVERLAY).isEmpty())
        assertEquals(before, plays(editor.timeline))

        // ---- uncompound: everything goes back on its track
        if (editor.timeline.selected?.id != grown.id) rule.onNodeWithTag("mainClip_0").performClick()
        pause(400)
        tool("Uncompound")
        Shots.take("45_multi_uncompound")
        assertTrue(editor.timeline.clips.none { it.isCompound })
        assertEquals(2, editor.timeline.main.size)
        assertEquals(2, editor.timeline.track(Track.OVERLAY).size)
        assertEquals(1, editor.timeline.track(Track.SFX).size)
        assertEquals(before, plays(editor.timeline))

        // ---- several clips deleted at once (one undo step)
        rule.onNodeWithTag("chip_OVERLAY_0").performTouchInput { longClick() }
        pause(400)
        rule.onNodeWithTag("chip_SFX_0").performClick()
        pause(400)
        rule.onNodeWithTag("pickDelete").performClick()
        pause(800)
        Shots.take("46_multi_deleted")
        assertEquals(1, editor.timeline.track(Track.OVERLAY).size)
        assertTrue(editor.timeline.track(Track.SFX).isEmpty())
        rule.onNodeWithContentDescription("Undo").performClick()
        pause(800)
        assertEquals(2, editor.timeline.track(Track.OVERLAY).size)
        assertEquals(1, editor.timeline.track(Track.SFX).size)
    }
}
