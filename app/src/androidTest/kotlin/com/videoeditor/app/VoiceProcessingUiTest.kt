package com.videoeditor.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.media.VoiceProcessing
import com.videoeditor.app.ui.EditorController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one-time processing of Voice enhance, like Edits, on a phone-like video (720x1280, 48 kHz
 * stereo, hiss under the voice): turning noise reduction on shows the Processing popup and the
 * twinkling dots over the video; the video keeps playing the whole time (it is never paused);
 * Minimize hides the popup while the work goes on; when done the noise reduction is heard; the
 * same file is never processed again, not even after reopening the project.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VoiceProcessingUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ctx get() = Fixtures.target
    private val editor: EditorController get() = EditorController.current ?: error("editor not open")

    @After fun normalSpeed() {
        VoiceProcessing.testSlowdown = 0f
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

    private fun shown(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private val positions = ArrayList<Pair<Long, Long>>()   // (wall time, player position)

    /** Records the playback position; checks the video keeps moving forward. */
    private fun sample() {
        val now = System.currentTimeMillis()
        if (positions.isNotEmpty() && now - positions.last().first < 450) return
        if (!editor.player.isPlaying.value) return
        positions += now to editor.player.positionMs.value
    }

    private fun assertKeptPlaying(what: String) {
        assertTrue("$what: still playing", editor.player.isPlaying.value)
        assertEquals("$what: no player errors (${editor.player.lastError})", 0, editor.player.errors)
    }

    private fun openEditor(projectId: Long) {
        waitFor("project listed") { rule.onAllNodes(hasText("Edited", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        pause(800)
        rule.onAllNodes(hasText("Edited", substring = true))[0].performClick()
        waitFor("editor loaded") { EditorController.current?.loaded == true && EditorController.current?.projectId == projectId }
        pause(1_000)
    }

    /** Waits without asking the screen to settle (it never settles while a video plays). */
    private fun poll(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) {
                Shots.take("FAILED_$what")
                throw AssertionError("timed out waiting for: $what")
            }
            nearEndRestart()
            sample()
            Thread.sleep(100)
        }
    }

    /** The emulator can be slow: start the video again rather than let it reach its end. */
    private fun nearEndRestart() {
        if (editor.player.isPlaying.value && editor.player.positionMs.value > editor.timeline.durationMs - 3_000) {
            rule.runOnUiThread { editor.player.seekTo(0) }
            positions.clear()
            Thread.sleep(600)
        }
    }

    /** Sleeps while the video plays, keeping track of its position. */
    private fun playFor(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            nearEndRestart()
            sample()
            Thread.sleep(100)
        }
    }

    private fun ui(block: () -> Unit) = rule.runOnUiThread(block)

    /** Screenshot of what is really on screen now (the test clock drives the drawing). */
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(700)
        Thread.sleep(400)
        Shots.take(name)
    }

    @Test fun processingRunsOnceWithPopupAndSparklesWhileTheVideoKeepsPlaying() {
        VoiceProcessing.clearAll(ctx)
        val app = ctx.applicationContext as App
        val info = Fixtures.info("phone_test.mp4")
        val projectId = runBlocking { app.projects.create(listOf(info, info)) }   // the same file twice
        openEditor(projectId)
        val first = editor.timeline.main[0].id
        val second = editor.timeline.main[1].id
        val uri = editor.timeline.main[0].sourceUri

        ui { editor.select(first) }
        pause(500)
        rule.onAllNodes(hasText("Voice enhance"))[0].performClick()
        waitFor("panel") { shown("voicePanel") }
        rule.onNodeWithTag("voiceTab_ENHANCE").performClick()
        pause(300)

        // ---- play; while playing move Reduce noise like a finger does (many small changes, then let go)
        ui { editor.player.play() }
        playFor(1_500)
        assertKeptPlaying("before")
        VoiceProcessing.testSlowdown = 0.4f          // ~8 s for the 20 s file, so the popup can be looked at
        val decodedBefore = VoiceProcessing.decodedFiles
        for (i in 1..20) {
            ui { editor.setVoiceDenoise(0.035f * i) }
            Thread.sleep(40)
        }
        ui { editor.voiceDone() }
        playFor(1_500)
        assertKeptPlaying("noise reduction switched on while playing")
        assertTrue("processing runs", editor.processingProgress != null)
        assertTrue("popup shown", editor.processingDialogShown)
        shot("75_voice_processing_popup")
        assertEquals(0.7f, editor.timeline.byId(first)!!.audio.denoise, 0.001f)

        // more changes while playing and processing: volume, voice
        ui { editor.setVoiceVolume(0.8f, live = false) }
        ui { editor.setVoiceEnhance(0.4f, live = false) }
        playFor(1_000)
        assertKeptPlaying("edited while processing")

        // ---- look at the popup and the dots (paused, so the screen can settle), Minimize
        ui { editor.player.pause() }
        pause(400)
        assertTrue("popup text", rule.onAllNodes(hasText("This only needs to happen once", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue("dots over the video", shown("voiceSparkles"))
        assertTrue("Processing… next to Reduce noise", rule.onAllNodes(hasText("Processing…")).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("voiceMinimize").performClick()
        Thread.sleep(500)
        assertTrue("popup minimized", !editor.processingDialogShown)
        assertTrue("popup gone from the screen", !shown("voiceProcessing"))
        assertTrue("progress line in the panel", shown("voiceProcessingLine"))
        shot("76_voice_processing_minimized")
        assertTrue("still processing after Minimize", editor.processingProgress != null)
        positions.clear()
        ui { editor.player.play() }

        poll("processing finished", 120_000) { editor.processingProgress == null }
        playFor(1_000)
        assertKeptPlaying("after processing")
        assertTrue("popup gone", !editor.processingDialogShown)
        assertNotNull("noise learned", VoiceProcessing.profile(uri))
        assertEquals("decoded once", decodedBefore + 1, VoiceProcessing.decodedFiles)
        assertNotNull("the preview uses the learned noise", editor.player.mainFx.plan.first { it.key == first }.profile)
        Report.note("voice processing: noise of the phone video %.1f dBFS".format(VoiceProcessing.profile(uri)!!.levelDb))

        // the video moved on all the time while it was processed (never paused, never stuck)
        val moves = positions.zipWithNext { a, b -> (b.first - a.first) to (b.second - a.second) }
        Report.note("voice processing: ${moves.size} position checks while processing, smallest step ${moves.minOfOrNull { it.second }} ms")
        assertTrue(moves.size > 5)
        for ((wall, pos) in moves) assertTrue("playback moved on ($pos ms in $wall ms)", pos > wall / 3)

        // the noise reduction is heard in the preview: a pause of the voice
        val fx = editor.player.mainFx
        fx.powerIn.clear(); fx.powerOut.clear()
        ui { editor.player.seekTo(4_400) }
        playFor(1_800)
        val g = 10 * kotlin.math.log10(fx.powerOut[first]!! / fx.powerIn[first]!!)
        Report.note("voice processing: preview noise in a pause %.1f dB; CPU for the sound %.1f%%".format(
            g, 100.0 * fx.processNanos / 1e9 / (fx.processFrames / 48_000.0)))
        assertTrue("noise reduced in the preview ($g dB)", g < -10)
        assertKeptPlaying("end")
        ui { editor.player.pause() }
        pause(400)
        shot("77_voice_processed")
        // on screen: no dots, no "Processing…", the Reduce noise value is back
        assertTrue("dots gone from the screen", !shown("voiceSparkles"))
        assertTrue("no Processing… left", rule.onAllNodes(hasText("Processing", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue("value shown", rule.onAllNodes(hasText("70%")).fetchSemanticsNodes().isNotEmpty())

        // the same file again (the second clip, "apply to all"): no second processing, no popup
        rule.onNodeWithTag("voiceTab_ENHANCE").performClick()
        pause(300)
        rule.onNodeWithTag("voiceApplyAll").performClick()
        pause(1_000)
        assertTrue(editor.timeline.byId(second)!!.audio.denoise > 0f)
        assertTrue("no popup for a known file", !shown("voiceProcessing"))
        assertEquals("not processed again", decodedBefore + 1, VoiceProcessing.decodedFiles)

        // app started again (memory empty): taken from the disk, not processed again
        rule.onNodeWithTag("voiceDone").performClick()
        pause(800)
        VoiceProcessing.clearMemory()
        ui {
            editor.undo()
            editor.redo()
        }
        waitFor("profile from disk") { VoiceProcessing.profile(uri) != null }
        assertEquals("not processed again after reopening", decodedBefore + 1, VoiceProcessing.decodedFiles)
        assertEquals("no player errors (${editor.player.lastError})", 0, editor.player.errors)
    }
}
