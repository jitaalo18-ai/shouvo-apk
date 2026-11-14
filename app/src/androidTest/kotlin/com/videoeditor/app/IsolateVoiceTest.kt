package com.videoeditor.app

import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.media3.common.C
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.captions.SpeechAudio
import com.videoeditor.app.data.clipFrom
import com.videoeditor.app.media.Exporter
import com.videoeditor.app.media.MediaProbe
import com.videoeditor.app.media.PlaybackController
import com.videoeditor.app.media.VoiceIsolationNative
import com.videoeditor.app.media.VoiceProcessing
import com.videoeditor.app.ui.EditorController
import com.videoeditor.core.AudioFx
import com.videoeditor.core.Resampler
import com.videoeditor.core.Timeline
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import com.videoeditor.core.VoiceEdits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Isolate voice on the device, with real Bangla speech (Google FLEURS) mixed with traffic and
 * laptop music (ci/caption-test-data.sh), at 48 and 44.1 kHz: the neural network keeps the voice
 * and removes the rest, the result stays in step with the picture, the preview and the export
 * play it, and the toolbar button works on the whole main timeline while the video keeps playing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class IsolateVoiceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ctx get() = Fixtures.target
    private val editor: EditorController get() = EditorController.current ?: error("editor not open")

    @After fun normalSpeed() {
        VoiceProcessing.testSlowdown = 0f
    }

    private fun readWav(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        b.position(44)
        val n = (f.length().toInt() - 44) / 2
        return FloatArray(n) { b.short / 32768f }
    }

    /** The sound of a media file as 48 kHz mono, sample 0 = time 0 (like the isolation does it). */
    private fun decode48(uri: Uri): FloatArray = runBlocking {
        val out = ArrayList<FloatArray>()
        var resampler: Resampler? = null
        var channels = 1
        var lead = -1L
        VoiceProcessing.decodeAudio(ctx, uri, onFormat = { rate, ch ->
            resampler = Resampler(rate, 48_000)
            channels = ch
            true
        }, onPcm = { pcm, frames, pts ->
            if (lead < 0) {
                lead = Math.round(pts * 48_000 / 1e6)
                out += FloatArray(lead.toInt())
            }
            val mono = FloatArray(frames) { f -> (0 until channels).map { pcm[f * channels + it] }.sum() / channels }
            out += resampler!!.feed(mono, frames)
        }, onProgress = {})
        out += resampler!!.finish()
        val all = FloatArray(out.sumOf { it.size })
        var p = 0
        for (a in out) {
            System.arraycopy(a, 0, all, p, a.size)
            p += a.size
        }
        all
    }

    private fun db(x: Double) = 10 * log10(max(x, 1e-20))

    /** Sample shift of [a] against [b] (best match within +-[range]). */
    private fun lag(a: FloatArray, b: FloatArray, rate: Int, range: Int): Int {
        val from = rate
        val to = minOf(rate * 6, a.size - range, b.size - range)
        var best = 0
        var bestV = Double.NEGATIVE_INFINITY
        for (l in -range..range) {
            var v = 0.0
            var i = from
            while (i < to) {
                v += a[i + l].toDouble() * b[i]
                i += 2
            }
            if (v > bestV) {
                bestV = v
                best = l
            }
        }
        return best
    }

    private fun siSdr(est: FloatArray, ref: FloatArray): Double {
        val n = minOf(est.size, ref.size)
        var dot = 0.0
        var rr = 0.0
        for (i in 0 until n) {
            dot += est[i].toDouble() * ref[i]
            rr += ref[i].toDouble() * ref[i]
        }
        val a = dot / rr
        var t = 0.0
        var e = 0.0
        for (i in 0 until n) {
            val ti = a * ref[i]
            t += ti * ti
            e += (est[i] - ti) * (est[i] - ti)
        }
        return db(t / e)
    }

    /** Frames (10 ms) where the clean speech is silent, and the levels of a signal there / in speech. */
    private class Frames(clean: FloatArray) {
        val fr = 480
        val count = clean.size / fr
        private val level = DoubleArray(count) { f -> (0 until fr).sumOf { clean[f * fr + it].toDouble() * clean[f * fr + it] } / fr }
        private val top = level.max()
        val silent = BooleanArray(count) { level[it] < top * Math.pow(10.0, -4.5) }

        fun medianPause(x: FloatArray): Double {
            val v = (0 until minOf(count, x.size / fr)).filter { silent[it] }.map { f -> (0 until fr).sumOf { x[f * fr + it].toDouble() * x[f * fr + it] } / fr }.sorted()
            return 10 * log10(max(v[v.size / 2], 1e-20))
        }

        fun speechPower(x: FloatArray): Double {
            val v = (0 until minOf(count, x.size / fr)).filter { !silent[it] }.map { f -> (0 until fr).sumOf { x[f * fr + it].toDouble() * x[f * fr + it] } / fr }
            return 10 * log10(v.average())
        }
    }

    @Test fun theNetworkKeepsTheVoiceAndRemovesTheRest() {
        assertTrue("isolation engine in the APK", VoiceIsolationNative.loaded)
        assertTrue(VoiceIsolationNative.model(ctx).length() > 7_000_000)
        val cleanFile = Pushed.file("iso_clean.wav") ?: error("test material missing (ci/caption-test-data.sh)")
        val clean = readWav(cleanFile)
        val frames = Frames(clean)
        Report.note("isolate: clean speech %.1f s, %d of %d frames are pauses".format(clean.size / 48_000.0, frames.silent.count { it }, frames.count))
        for (rate in listOf(48_000, 44_100)) {
            VoiceProcessing.clearAll(ctx)
            val file = Pushed.file("iso_noisy_$rate.mp4")!!
            val uri = Uri.fromFile(file).toString()
            val t0 = System.currentTimeMillis()
            val out = runBlocking { VoiceProcessing.ensureIsolation(ctx, uri) }
            val ms = System.currentTimeMillis() - t0
            assertNotNull("isolated ($rate)", out)
            val voice = readWav(out!!)
            val noisy = decode48(Uri.fromFile(file))
            val info = MediaProbe.probe(ctx, Uri.fromFile(file))!!
            val inLag = lag(noisy, clean, 48_000, 2_400)
            val outLag = lag(voice, clean, 48_000, 2_400)
            val sdrIn = siSdr(noisy, clean)
            val sdrOut = siSdr(voice, clean)
            val pauseIn = frames.medianPause(noisy)
            val pauseOut = frames.medianPause(voice)
            val speechIn = frames.speechPower(clean)
            val speechOut = frames.speechPower(voice)
            Report.note(
                "isolate $rate Hz: %.1f s of sound in %.1f s; SI-SDR %.1f -> %.1f dB; pauses %.1f -> %.1f dBFS; voice %.1f -> %.1f dBFS; shift input %d, voice %d samples; file %.2f s for a %.2f s video".format(
                    clean.size / 48_000.0, ms / 1000.0, sdrIn, sdrOut, pauseIn, pauseOut, speechIn, speechOut, inLag, outLag,
                    voice.size / 48_000.0, info.durationMs / 1000.0,
                ),
            )
            assertTrue("voice in step with the picture (shift $outLag samples)", abs(outLag - inLag) <= 48)
            assertTrue("clearer: SI-SDR $sdrIn -> $sdrOut", sdrOut > sdrIn + 6)
            assertTrue("noise gone from the pauses: $pauseIn -> $pauseOut", pauseOut < pauseIn - 20)
            assertTrue("voice kept: $speechIn -> $speechOut", abs(speechOut - speechIn) < 3)
            assertTrue("as long as the video", abs(voice.size / 48.0 - info.durationMs) < 100)
        }
        // processed once: asking again takes it from memory / disk
        val again = VoiceProcessing.isolatedFiles
        runBlocking { VoiceProcessing.ensureIsolation(ctx, Uri.fromFile(Pushed.file("iso_noisy_44100.mp4")!!).toString()) }
        VoiceProcessing.clearMemory()
        runBlocking { VoiceProcessing.ensureIsolation(ctx, Uri.fromFile(Pushed.file("iso_noisy_44100.mp4")!!).toString()) }
        assertEquals("never twice", again, VoiceProcessing.isolatedFiles)
    }

    @Test fun previewAndExportPlayTheIsolatedVoice() = runBlocking {
        val file = Pushed.file("iso_noisy_48000.mp4") ?: error("test material missing")
        val clean = readWav(Pushed.file("iso_clean.wav")!!)
        val frames = Frames(clean)
        val base = clipFrom(MediaProbe.probe(ctx, Uri.fromFile(file))!!, Track.MAIN)
        VoiceProcessing.ensureIsolation(ctx, base.sourceUri)
        val plainT = TimelineOps.appendMain(Timeline(), listOf(base))
        val isoT = VoiceEdits.isolateMain(plainT, true)

        // preview: the main player plays the voice file (48 kHz mono) and the picture of the video
        withContext(Dispatchers.Main) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val player = PlaybackController(ctx, scope)
            try {
                // a tiny volume change makes the sound processing count what comes in
                val t = Timeline(isoT.clips.map { it.copy(audio = it.audio.copy(volume = 0.99f)) })
                player.setTimeline(t)
                delay(1_500)
                player.play()
                delay(2_500)
                player.pause()
                val audio = player.main.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                val f = (0 until audio.length).map { audio.getTrackFormat(it) }.first { true }
                val hasVideo = player.main.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                Report.note("isolate preview: sound ${f.sampleMimeType} ${f.sampleRate} Hz ${f.channelCount} ch, picture ${if (hasVideo) "yes" else "no"}, errors ${player.errors}")
                assertEquals(48_000, f.sampleRate)
                assertEquals("the isolated (mono) voice plays", 1, f.channelCount)
                assertTrue("the picture still plays", hasVideo)
                assertEquals(0, player.errors)
                // switching it off: the clip's own sound again
                player.setTimeline(plainT)
                delay(1_500)
                val own = player.main.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }.getTrackFormat(0)
                assertEquals("own sound back", 2, own.channelCount)
            } finally {
                player.release()
                scope.cancel()
            }
        }

        // export: the voice without the noise, in step with the picture
        suspend fun exported(t: Timeline): FloatArray {
            val out = Exporter(ctx).export(t, 16f / 9f) { }
            try {
                val info = MediaProbe.probe(ctx, out)!!
                assertTrue(abs(info.durationMs - t.durationMs) < 500)
                return SpeechAudio.clip(ctx, clipFrom(info, Track.MAIN))
            } finally {
                ctx.contentResolver.delete(out, null, null)
            }
        }
        val plain = exported(plainT)
        val iso = exported(isoT)
        // compare at 16 kHz
        val r = Resampler(48_000, 16_000)
        val clean16 = r.feed(clean, clean.size) + r.finish()
        val f16 = Frames16(clean16)
        val pausePlain = f16.medianPause(plain)
        val pauseIso = f16.medianPause(iso)
        val shift = lag(iso, clean16, 16_000, 800)
        val shiftPlain = lag(plain, clean16, 16_000, 800)
        Report.note("isolate export: pauses %.1f -> %.1f dBFS; shift against clean speech: plain %d, isolated %d samples (16 kHz)".format(pausePlain, pauseIso, shiftPlain, shift))
        assertTrue("noise gone in the export", pauseIso < pausePlain - 20)
        assertTrue("in step with the picture", abs(shift - shiftPlain) <= 16)
        assertTrue(frames.count > 0)
    }

    /** [Frames] for 16 kHz. */
    private class Frames16(clean: FloatArray) {
        val fr = 160
        val count = clean.size / fr
        private val level = DoubleArray(count) { f -> (0 until fr).sumOf { clean[f * fr + it].toDouble() * clean[f * fr + it] } / fr }
        private val top = level.max()
        val silent = BooleanArray(count) { level[it] < top * Math.pow(10.0, -4.5) }
        fun medianPause(x: FloatArray): Double {
            val v = (0 until minOf(count, x.size / fr)).filter { silent[it] }.map { f -> (0 until fr).sumOf { x[f * fr + it].toDouble() * x[f * fr + it] } / fr }.sorted()
            return 10 * log10(max(v[v.size / 2], 1e-20))
        }
    }

    // ---- the button --------------------------------------------------------------------------

    private fun pause(ms: Long) {
        Thread.sleep(ms)
        rule.waitForIdle()
    }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) { condition() }
        } catch (e: Throwable) {
            Shots.take("FAILED_$what")
            throw AssertionError("timed out waiting for: $what", e)
        }
    }

    @Test fun isolateVoiceButtonWorksOnTheWholeTimelineWhileItPlays() {
        VoiceProcessing.clearAll(ctx)
        val app = ctx.applicationContext as App
        val noisy = MediaProbe.probe(ctx, Uri.fromFile(Pushed.file("iso_noisy_48000.mp4") ?: error("test material missing")))!!
        val projectId = runBlocking { app.projects.create(listOf(noisy, Fixtures.info("phone_test.mp4"))) }
        waitFor("project listed") { rule.onAllNodes(hasText("Edited", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        pause(800)
        rule.onAllNodes(hasText("Edited", substring = true))[0].performClick()
        waitFor("editor loaded") { EditorController.current?.loaded == true && EditorController.current?.projectId == projectId }
        pause(1_000)
        assertTrue(!editor.isolateOn)

        // tap the button (nothing selected), then play while it works
        rule.onAllNodes(hasText("Isolate voice"))[0].performClick()
        rule.runOnUiThread { editor.player.play() }
        VoiceProcessing.testSlowdown = 0f
        Thread.sleep(1_200)
        assertTrue("on for the whole timeline", editor.isolateOn)
        assertTrue("both clips", VoiceEdits.mainVoices(editor.timeline).all { it.audio.isolate })
        assertTrue("popup", editor.processingDialogShown || editor.processingProgress == null)
        rule.mainClock.advanceTimeBy(700)
        Thread.sleep(300)
        Shots.take("80_isolate_processing")
        // keeps playing, never an error
        val positions = ArrayList<Long>()
        val end = System.currentTimeMillis() + 60_000
        while (editor.processingProgress != null && System.currentTimeMillis() < end) {
            positions += editor.player.positionMs.value
            assertTrue("still playing", editor.player.isPlaying.value)
            Thread.sleep(400)
        }
        assertTrue("finished", editor.processingProgress == null)
        Thread.sleep(1_500)
        assertTrue("still playing after", editor.player.isPlaying.value)
        assertEquals("no player errors (${editor.player.lastError})", 0, editor.player.errors)
        Report.note("isolate button: processed while playing, positions $positions")
        for (i in 1 until positions.size) assertTrue("never jumped back (${positions[i - 1]} -> ${positions[i]})", positions[i] >= positions[i - 1] - 300)
        if (positions.size > 3) assertTrue("moved on", positions.last() - positions.first() > (positions.size - 1) * 400 / 3)
        rule.runOnUiThread { editor.player.pause() }
        pause(500)
        Shots.take("81_isolate_on")
        // the player plays the voices now
        val audio = editor.player.main.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }.getTrackFormat(0)
        assertEquals(1, audio.channelCount)

        // tap again: off
        rule.onAllNodes(hasText("Isolate voice"))[0].performClick()
        pause(800)
        assertTrue(!editor.isolateOn)
        assertTrue(VoiceEdits.mainVoices(editor.timeline).none { it.audio.isolate })
        // undo brings it back
        rule.runOnUiThread { editor.undo() }
        pause(500)
        assertTrue(editor.isolateOn)
    }
}
