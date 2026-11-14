package com.videoeditor.app

import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.captions.SpeechAudio
import com.videoeditor.app.data.clipFrom
import com.videoeditor.app.media.Exporter
import com.videoeditor.app.media.MediaProbe
import com.videoeditor.app.media.PlaybackController
import com.videoeditor.app.ui.EditorController
import com.videoeditor.core.AudioFx
import com.videoeditor.core.Clip
import com.videoeditor.core.Timeline
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Voice enhance on the device: the export really changes the sound (noise, volume, fades, also
 * for sound on other tracks), the preview player processes each clip's sound with its own
 * settings, and the panel works like Edits' (Auto / Volume / Enhance / Fade).
 *
 * noisy_test.mp4: 8 s, a "voice" at about -22 dBFS from 1.0 to 4.0 s and from 6.4 s, hiss at
 * about -39 dBFS everywhere. portrait_test.mp4: 6 s of a steady tone (not noise).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VoiceEnhanceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ctx get() = Fixtures.target
    private val editor: EditorController get() = EditorController.current ?: error("editor not open")

    /** Level (dBFS) of [fromS]..[toS] seconds of 16 kHz mono [x]. */
    private fun level(x: FloatArray, fromS: Double, toS: Double): Double {
        val a = (fromS * SpeechAudio.RATE).toInt()
        val b = minOf((toS * SpeechAudio.RATE).toInt(), x.size)
        var s = 0.0
        for (i in a until b) s += x[i].toDouble() * x[i]
        return 10 * log10(max(s / max(1, b - a), 1e-12))
    }

    private suspend fun exportSound(t: Timeline): FloatArray {
        val out: Uri = Exporter(ctx).export(t, 16f / 9f) { }
        try {
            val info = MediaProbe.probe(ctx, out)!!
            assertTrue("export has sound", info.hasAudio)
            assertTrue("export length ${info.durationMs}", abs(info.durationMs - t.durationMs) < 500)
            return SpeechAudio.clip(ctx, clipFrom(info, Track.MAIN))
        } finally {
            ctx.contentResolver.delete(out, null, null)
        }
    }

    private fun with(c: Clip, fx: AudioFx) = TimelineOps.appendMain(Timeline(), listOf(c.copy(audio = fx)))

    @Test fun exportAppliesNoiseReductionVolumeAndFades() = runBlocking {
        val noisy = clipFrom(Fixtures.info("noisy_test.mp4"), Track.MAIN)
        val plain = exportSound(with(noisy, AudioFx.NONE))
        val clean = exportSound(with(noisy, AudioFx(denoise = 1f)))
        val auto = exportSound(with(noisy, AudioFx(denoise = AudioFx.AUTO_DENOISE)))
        val quiet = exportSound(with(noisy, AudioFx(volume = 0.5f, fadeInMs = 3_000)))

        val pausePlain = level(plain, 4.4, 6.2)
        val speechPlain = level(plain, 1.2, 3.8)
        Report.note("voice export: plain pause %.1f dB, voice %.1f dB".format(pausePlain, speechPlain))
        Report.note("voice export: reduce noise 100%%: pause %.1f dB, voice %.1f dB".format(level(clean, 4.4, 6.2), level(clean, 1.2, 3.8)))
        Report.note("voice export: reduce noise 70%%: pause %.1f dB".format(level(auto, 4.4, 6.2)))
        assertTrue("noise reduced (100 %)", level(clean, 4.4, 6.2) < pausePlain - 12)
        assertTrue("noise reduced (70 %)", level(auto, 4.4, 6.2) < pausePlain - 10)
        assertTrue("voice kept", abs(level(clean, 1.2, 3.8) - speechPlain) < 4)

        // volume 50 % = -6 dB once the fade is over; during the fade much quieter
        val after = level(quiet, 3.2, 3.9) - level(plain, 3.2, 3.9)
        val during = level(quiet, 1.0, 1.5) - level(plain, 1.0, 1.5)
        Report.note("voice export: volume 50%% -> %.1f dB, during the 3 s fade in %.1f dB".format(after, during))
        assertEquals(-6.0, after, 1.5)
        assertTrue("fade in", during < -10)
    }

    @Test fun exportAppliesSettingsOfSoundOnOtherTracks() = runBlocking {
        // the main video is muted, the overlay's sound is cleaned: what is heard is the clean overlay
        val main = clipFrom(Fixtures.info("portrait_test.mp4"), Track.MAIN).copy(audio = AudioFx(volume = 0f))
        val overlay = clipFrom(Fixtures.info("noisy_test.mp4"), Track.OVERLAY, 0)
        var t = TimelineOps.appendMain(Timeline(), listOf(main))
        t = TimelineOps.addClip(t, overlay)
        val noisyMix = exportSound(t)
        t = Timeline(t.clips.map { if (it.id == overlay.id) it.copy(audio = AudioFx(denoise = 1f)) else it })
        val cleanMix = exportSound(t)
        Report.note("voice export (overlay sound): pause %.1f dB -> %.1f dB".format(level(noisyMix, 4.4, 5.8), level(cleanMix, 4.4, 5.8)))
        assertTrue("main muted: the pause is only the overlay's hiss", level(noisyMix, 4.4, 5.8) < -33)
        assertTrue("overlay noise reduced", level(cleanMix, 4.4, 5.8) < level(noisyMix, 4.4, 5.8) - 12)
        assertTrue("overlay voice still there", level(cleanMix, 1.2, 3.8) > -32)
    }

    @Test fun previewProcessesTheSoundOfEachClip() = runBlocking {
        withContext(Dispatchers.Main) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val player = PlaybackController(ctx, scope)
            try {
                val a = clipFrom(Fixtures.info("noisy_test.mp4"), Track.MAIN).copy(audio = AudioFx(volume = 0.5f))
                val b = clipFrom(Fixtures.info("portrait_test.mp4"), Track.MAIN)
                val t = TimelineOps.appendMain(Timeline(), listOf(a, b))
                player.setTimeline(t)
                delay(1_500)
                val fx = player.mainFx
                assertTrue("processing on", fx.active)

                // a jump lands in the right clip at the right time
                for (target in listOf(2_000L, 9_000L, 6_000L)) {
                    val flushes = fx.flushes
                    fx.firstAfterFlushUs = -1
                    player.seekTo(target)
                    player.play()
                    var waited = 0
                    while ((fx.flushes == flushes || fx.firstAfterFlushUs < 0) && waited < 5_000) {
                        delay(50)
                        waited += 50
                    }
                    delay(600)
                    player.pause()
                    Report.note("voice preview: seek to $target ms -> first sound mapped to ${fx.firstAfterFlushUs / 1000} ms")
                    assertTrue("seek to $target: ${fx.firstAfterFlushUs / 1000}", abs(fx.firstAfterFlushUs / 1000 - target) < 150)
                }

                // play across the cut: both clips' sound goes through, each with its own volume
                fx.framesByKey.clear(); fx.powerIn.clear(); fx.powerOut.clear()
                player.seekTo(6_000)
                delay(400)
                player.play()
                delay(4_000)
                player.pause()
                val ga = 10 * log10(fx.powerOut[a.id]!! / fx.powerIn[a.id]!!)
                val gb = 10 * log10(fx.powerOut[b.id]!! / fx.powerIn[b.id]!!)
                Report.note("voice preview: clip with volume 50%% %.1f dB, clip at 100%% %.1f dB (frames ${fx.framesByKey})".format(ga, gb))
                assertTrue(fx.framesByKey.getOrDefault(a.id, 0) > 10_000)
                assertTrue(fx.framesByKey.getOrDefault(b.id, 0) > 10_000)
                assertEquals(-6.0, ga, 1.5)
                assertEquals(0.0, gb, 1.0)

                // a change while playing is heard: b muted
                fx.powerIn.clear(); fx.powerOut.clear()
                player.setTimeline(Timeline(t.clips.map { if (it.id == b.id) it.copy(audio = AudioFx(volume = 0f)) else it }))
                player.seekTo(8_500)
                player.play()
                delay(2_000)
                player.pause()
                val gMuted = 10 * log10(max(fx.powerOut[b.id] ?: 0.0, 1e-12) / fx.powerIn[b.id]!!)
                Report.note("voice preview: after muting the clip %.1f dB".format(gMuted))
                assertTrue("muted", gMuted < -30)
            } finally {
                player.release()
                scope.cancel()
            }
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
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

    private fun selectClip(id: String) {
        rule.runOnUiThread { if (editor.timeline.selected?.id != id) editor.select(id) }
    }

    private fun clip(id: String) = editor.timeline.byId(id)!!

    /** Taps slider [tag] at [fraction] of its length. */
    private fun setSlider(tag: String, fraction: Float) {
        rule.onNodeWithTag(tag).performTouchInput {
            down(percentOffset(0.02f, 0.5f))
            moveTo(percentOffset(fraction, 0.5f))
            up()
        }
        pause(400)
    }

    @Test fun voiceEnhancePanelWorksLikeEdits() {
        val app = ctx.applicationContext as App
        val projectId = runBlocking { app.projects.create(listOf(Fixtures.info("noisy_test.mp4"), Fixtures.info("portrait_test.mp4"))) }
        waitFor("project listed") { rule.onAllNodes(hasText("Edited", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        pause(800)
        rule.onAllNodes(hasText("Edited", substring = true))[0].performClick()
        waitFor("editor loaded") { EditorController.current?.loaded == true && EditorController.current?.projectId == projectId }
        pause(1_000)
        val noisyId = editor.timeline.main[0].id
        val toneId = editor.timeline.main[1].id
        selectClip(noisyId)
        pause(600)

        // ---- the toolbar button opens the panel
        rule.onAllNodes(hasText("Voice enhance"))[0].performClick()
        waitFor("panel") { rule.onAllNodesWithTag("voicePanel").fetchSemanticsNodes().isNotEmpty() }

        // ---- Auto: finds the one noisy clip (the tone is not noise) and fixes it
        rule.onNodeWithTag("voiceTab_AUTO").performClick()
        waitFor("analysis") { editor.noisyClipCount != null }
        pause(400)
        Shots.take("70_voice_auto")
        assertEquals(1, editor.noisyClipCount)
        assertTrue(rule.onAllNodes(hasText("Remove noise on 1 clip")).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("voiceRemoveNoise").performClick()
        pause(500)
        assertEquals(AudioFx.AUTO_DENOISE, clip(noisyId).audio.denoise)
        assertEquals(0f, clip(toneId).audio.denoise)
        assertTrue(rule.onAllNodes(hasText("Noise removed on 1 clip")).fetchSemanticsNodes().isNotEmpty())
        Shots.take("71_voice_noise_removed")

        rule.onNodeWithTag("voiceAutoAdjust").performClick()
        waitFor("auto adjust") { clip(toneId).audio.volume != 1f }
        Report.note("voice ui: auto adjust volumes ${clip(noisyId).audio.volume} / ${clip(toneId).audio.volume}")
        assertTrue(clip(noisyId).audio.volume in 0.8f..1.6f)
        assertTrue(clip(toneId).audio.volume in 0.8f..1.6f)
        rule.runOnUiThread { editor.undo() }
        pause(400)
        assertEquals("undo takes the auto volume back", 1f, clip(toneId).audio.volume)
        assertEquals("but keeps the noise removal", AudioFx.AUTO_DENOISE, clip(noisyId).audio.denoise)

        // ---- Enhance: sliders and "apply to all clips"
        rule.onNodeWithTag("voiceTab_ENHANCE").performClick()
        pause(300)
        setSlider("voiceDenoise", 0.4f)
        val d = clip(noisyId).audio.denoise
        assertTrue("reduce noise $d", abs(d - 0.4f) < 0.06f)
        assertEquals("only this clip", 0f, clip(toneId).audio.denoise)
        rule.onNodeWithTag("voiceApplyAll").performClick()
        pause(400)
        assertEquals("applied to all", d, clip(toneId).audio.denoise)
        assertTrue(editor.voiceApplyAll)
        setSlider("voiceEnhance", 0.6f)
        val e = clip(noisyId).audio.enhance
        assertTrue("enhance voice $e", abs(e - 0.6f) < 0.06f)
        assertEquals("with apply to all, both change", e, clip(toneId).audio.enhance)
        Shots.take("72_voice_enhance")
        // one drag = one undo step
        rule.runOnUiThread { editor.undo() }
        pause(300)
        assertEquals(0f, clip(noisyId).audio.enhance)
        rule.runOnUiThread { editor.redo() }
        pause(300)
        assertEquals(e, clip(noisyId).audio.enhance)

        // ---- Volume (only this clip)
        rule.onNodeWithTag("voiceTab_VOLUME").performClick()
        pause(300)
        setSlider("voiceVolume", 0.25f)
        val v = clip(noisyId).audio.volume
        assertTrue("volume $v", abs(v - 0.5f) < 0.08f)
        assertEquals(1f, clip(toneId).audio.volume)
        Shots.take("73_voice_volume")

        // ---- Fade (at most half the clip: 4 s for the 8 s clip)
        rule.onNodeWithTag("voiceTab_FADE").performClick()
        pause(300)
        setSlider("voiceFadeIn", 0.5f)
        setSlider("voiceFadeOut", 1f)
        val fi = clip(noisyId).audio.fadeInMs
        val fo = clip(noisyId).audio.fadeOutMs
        assertTrue("fade in $fi", abs(fi - 2_000) <= 300)
        assertEquals(4_000L, fo)
        Shots.take("74_voice_fade")

        // the preview player got the settings
        assertTrue(editor.player.mainFx.active)
        assertEquals(clip(noisyId).audio, editor.player.mainFx.plan.first { it.key == noisyId }.fx)

        // ---- done: closes; everything is saved
        rule.onNodeWithTag("voiceDone").performClick()
        pause(800)
        assertTrue(rule.onAllNodes(hasTestTag("voicePanel")).fetchSemanticsNodes().isEmpty())
        val saved = runBlocking { app.projects.load(projectId) }
        assertEquals(clip(noisyId).audio, saved.byId(noisyId)!!.audio)
        assertEquals(clip(toneId).audio, saved.byId(toneId)!!.audio)
        Report.note("voice ui: saved ${saved.byId(noisyId)!!.audio}")

        // the panel follows the selection and closes with it
        rule.onAllNodes(hasText("Voice enhance"))[0].performClick()
        waitFor("panel again") { rule.onAllNodesWithTag("voicePanel").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnUiThread { editor.select(null) }
        waitFor("panel closed with the selection") { rule.onAllNodesWithTag("voicePanel").fetchSemanticsNodes().isEmpty() }
    }
}
