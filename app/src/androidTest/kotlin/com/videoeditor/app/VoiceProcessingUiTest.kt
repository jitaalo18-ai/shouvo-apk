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

    @Test fun processingRunsOnceWithPopupAndSparklesWhileTheVideoKeepsPlaying() {
        VoiceProcessing.clearAll(ctx)
        val app = ctx.applicationContext as App
        val info = Fixtures.info("phone_test.mp4")
        val projectId = runBlocking { app.projects.create(listOf(info, info)) }   // the same file twice
        openEditor(projectId)
        val first = editor.timeline.main[0].id
        val second = editor.timeline.main[1].id
        val uri = editor.timeline.main[0].sourceUri

        rule.runOnUiThread { editor.select(first) }
        pause(500)
        rule.onAllNodes(hasText("Voice enhance"))[0].performClick()
        waitFor("panel") { shown("voicePanel") }
        rule.onNodeWithTag("voiceTab_ENHANCE").performClick()
        pause(300)

        // play, then turn noise reduction on while playing
        rule.runOnUiThread { editor.player.play() }
        pause(1_200)
        assertKeptPlaying("before")
        VoiceProcessing.testSlowdown = 0.35f          // ~7 s for the 20 s file, so the popup can be checked
        val decodedBefore = VoiceProcessing.decodedFiles
        rule.onNodeWithTag("voiceDenoise").performTouchInput {
            down(percentOffset(0.03f, 0.5f))
            for (i in 1..10) moveTo(percentOffset(0.03f + 0.67f * i / 10f, 0.5f))
            up()
        }
        waitFor("processing popup", 10_000) { shown("voiceProcessing") }
        sample()
        assertTrue("popup text", rule.onAllNodes(hasText("This only needs to happen once", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue("sparkles over the video", shown("voiceSparkles"))
        Shots.take("75_voice_processing_popup")
        assertKeptPlaying("popup open")
        val d = editor.timeline.byId(first)!!.audio.denoise
        assertTrue("reduce noise $d", d in 0.6f..0.8f)

        // Minimize: popup gone, dots stay, the work and the video go on
        pause(800)
        sample()
        rule.onNodeWithTag("voiceMinimize").performClick()
        pause(600)
        sample()
        assertTrue("popup minimized", !shown("voiceProcessing"))
        assertTrue("sparkles while it runs", shown("voiceSparkles"))
        assertTrue("progress line in the panel", shown("voiceProcessingLine"))
        Shots.take("76_voice_processing_minimized")
        assertKeptPlaying("minimized")
        // keep editing meanwhile: the volume of the clip
        rule.onNodeWithTag("voiceTab_VOLUME").performClick()
        pause(300)
        rule.onNodeWithTag("voiceVolume").performTouchInput {
            down(percentOffset(0.03f, 0.5f))
            for (i in 1..8) moveTo(percentOffset(0.03f + 0.4f * i / 8f, 0.5f))
            up()
        }
        pause(400)
        sample()
        assertTrue("edited while processing", editor.timeline.byId(first)!!.audio.volume < 1f)
        assertKeptPlaying("edited while processing")

        waitFor("processing finished", 40_000) {
            sample()
            editor.processingProgress == null
        }
        pause(600)
        sample()
        assertTrue("sparkles gone", !shown("voiceSparkles"))
        assertTrue("popup gone", !shown("voiceProcessing"))
        assertNotNull("noise learned", VoiceProcessing.profile(uri))
        assertEquals("decoded once", decodedBefore + 1, VoiceProcessing.decodedFiles)
        val seg = editor.player.mainFx.plan.first { it.key == first }
        assertNotNull("the preview uses the learned noise", seg.profile)
        assertKeptPlaying("after processing")
        Report.note("voice processing: noise of the phone video %.1f dBFS".format(VoiceProcessing.profile(uri)!!.levelDb))

        // the video moved on all the time (never paused, never stuck)
        val moves = positions.zipWithNext { a, b -> (b.first - a.first) to (b.second - a.second) }
        Report.note("voice processing: playback samples ${positions.size}, position steps ${moves.map { it.second }}")
        for ((wall, pos) in moves) {
            if (wall > 400 && pos > -1000) assertTrue("playback moved on ($pos ms in $wall ms)", pos > 0)
        }

        // the noise reduction is heard in the preview
        val fx = editor.player.mainFx
        fx.powerIn.clear(); fx.powerOut.clear()
        rule.runOnUiThread { editor.player.seekTo(4_400) }
        pause(1_800)
        val g = 10 * kotlin.math.log10(fx.powerOut[first]!! / fx.powerIn[first]!!)
        Report.note("voice processing: preview noise in a pause %.1f dB; CPU for the sound %.1f%%".format(
            g, 100.0 * fx.processNanos / 1e9 / (fx.processFrames / 48_000.0)))
        assertTrue("noise reduced in the preview ($g dB)", g < -10)
        rule.runOnUiThread { editor.player.pause() }

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
        rule.runOnUiThread {
            editor.undo()
            editor.redo()
        }
        waitFor("profile from disk") { VoiceProcessing.profile(uri) != null }
        assertEquals("not processed again after reopening", decodedBefore + 1, VoiceProcessing.decodedFiles)
        assertTrue(!shown("voiceSparkles"))
        Shots.take("77_voice_reopened")
    }
}
