package com.videoeditor.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.data.clipFrom
import com.videoeditor.app.media.ExportUnsupported
import com.videoeditor.app.media.Exporter
import com.videoeditor.app.ui.EditorController
import com.videoeditor.core.ExportSettings
import com.videoeditor.core.Timeline
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The Export sheet (resolution, frame rate) and "Export video" for one clip, measured on the
 * videos that come out: frame size, number of frames, length.
 */
@RunWith(AndroidJUnit4::class)
class ExportSettingsTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ctx get() = Fixtures.target
    private val editor: EditorController get() = EditorController.current ?: error("editor not open")

    @After fun defaultSettingsAgain() {
        ctx.getSharedPreferences("editor", 0).edit().clear().commit()
    }

    private class Video(val width: Int, val height: Int, val frames: Int, val durationMs: Long, val hasAudio: Boolean)

    /** Frame size (as shown), number of video frames and length of [uri]. */
    private fun measure(uri: Uri): Video {
        val x = MediaExtractor()
        try {
            x.setDataSource(ctx, uri, null)
            var w = 0
            var h = 0
            var frames = 0
            var dur = 0L
            var audio = false
            for (i in 0 until x.trackCount) {
                val f = x.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) audio = true
                if (!mime.startsWith("video/")) continue
                w = f.getInteger(MediaFormat.KEY_WIDTH)
                h = f.getInteger(MediaFormat.KEY_HEIGHT)
                val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                if (rot == 90 || rot == 270) w = h.also { h = w }
                dur = f.getLong(MediaFormat.KEY_DURATION) / 1000
                x.selectTrack(i)
                while (x.sampleTime >= 0) {
                    frames++
                    if (!x.advance()) break
                }
                x.unselectTrack(i)
            }
            return Video(w, h, frames, dur, audio)
        } finally {
            x.release()
        }
    }

    @Test fun resolutionAndFrameRateAreWhatWasChosen() = runBlocking {
        val clip = clipFrom(Fixtures.info("scenes_test.mp4"), Track.MAIN)        // 640x360, 30 fps, 7 s, 210 frames
        val t = TimelineOps.appendMain(Timeline(), listOf(clip))
        suspend fun out(s: ExportSettings): Video {
            val uri = Exporter(ctx).export(t, 16f / 9f, s) { }
            try {
                return measure(uri)
            } finally {
                ctx.contentResolver.delete(uri, null, null)
            }
        }
        val std = out(ExportSettings())
        Report.note("export settings: default -> ${std.width}x${std.height}, ${std.frames} frames, ${std.durationMs} ms")
        assertEquals(1920 to 1080, std.width to std.height)
        assertTrue("all frames kept at Auto (${std.frames})", std.frames in 205..212)

        val hd24 = out(ExportSettings(720, 24))
        Report.note("export settings: 720p / 24 fps -> ${hd24.width}x${hd24.height}, ${hd24.frames} frames, ${hd24.durationMs} ms")
        assertEquals(1280 to 720, hd24.width to hd24.height)
        assertTrue("24 fps: about 168 frames (${hd24.frames})", hd24.frames in 160..176)
        assertTrue("same length (${hd24.durationMs})", abs(hd24.durationMs - 7_000) < 200)
        assertTrue(hd24.hasAudio)

        val sd = out(ExportSettings(480, 60))
        Report.note("export settings: 480p / 60 fps -> ${sd.width}x${sd.height}, ${sd.frames} frames (a 30 fps video stays 30)")
        assertEquals(854 to 480, sd.width to sd.height)
        assertTrue("no frames invented (${sd.frames})", sd.frames in 205..212)

        // 2K and 4K: made at that size, or refused in plain words when this device's encoder cannot
        for (s in listOf(ExportSettings(1440, 30), ExportSettings(2160, 30))) {
            val (w, h) = s.canvasSize(16f / 9f)
            val can = Exporter(ctx).encoderSupports(w, h, 30)
            try {
                val v = out(s)
                Report.note("export settings: ${s.resolutionLabel} -> ${v.width}x${v.height}, ${v.frames} frames (encoder says it can: $can)")
                assertEquals(w to h, v.width to v.height)
                assertTrue(can)
            } catch (e: ExportUnsupported) {
                Report.note("export settings: ${s.resolutionLabel} refused on this emulator: ${e.message}")
                assertTrue("refused only when the encoder cannot", !can)
                assertTrue(e.message!!.contains("Choose a lower resolution"))
            }
        }
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

    @Test fun exportVideoSavesOnlyTheSelectedClipWithTheChosenSettings() {
        val app = ctx.applicationContext as App
        val projectId = runBlocking { app.projects.create(listOf(Fixtures.info("portrait_test.mp4"), Fixtures.info("scenes_test.mp4"), Fixtures.info("portrait_test.mp4"))) }
        waitFor("project listed") { rule.onAllNodes(hasText("Edited", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        pause(800)
        TestProjects.openNewest(rule)
        waitFor("editor loaded") { EditorController.current?.loaded == true && EditorController.current?.projectId == projectId }
        pause(1_000)
        val middle = editor.timeline.main[1]
        val before = editor.timeline

        // the badge next to Export shows the resolution and opens the sheet for the whole project
        rule.onNodeWithTag("exportBadge").assertTextContains("FHD")
        rule.onNodeWithTag("exportBadge").performClick()
        waitFor("sheet") { shown("exportSheet") }
        assertTrue(rule.onAllNodes(hasText("The whole project is saved to your Gallery.")).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Cancel").performClick()
        pause(400)
        assertTrue(!shown("exportSheet"))

        // Export video on the middle clip
        rule.runOnUiThread { editor.select(middle.id) }
        pause(600)
        rule.onNodeWithText("Export video").performScrollTo().performClick()
        waitFor("clip sheet") { shown("exportSheet") }
        assertTrue(rule.onAllNodes(hasText("Export this clip")).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("exportRes_720").performClick()
        rule.onNodeWithTag("exportFps_25").performScrollTo().performClick()
        pause(400)
        val (w, h) = editor.exportSize
        rule.onNodeWithTag("exportSummary").assertTextContains("$w x $h · up to 25 fps")
        Shots.take("97_export_sheet")
        assertEquals(ExportSettings(720, 25), editor.exportSettings)
        rule.onNodeWithTag("exportStart").performClick()
        waitFor("export done", 120_000) { editor.busy == null && editor.lastExport != null }
        pause(500)
        assertTrue(rule.onAllNodes(hasText("Saved this clip to Movies/VideoEditor in your Gallery.")).fetchSemanticsNodes().isNotEmpty())
        val uri = editor.lastExport
        assertNotNull(uri)
        try {
            val v = measure(uri!!)
            Report.note("export video (one clip): ${v.width}x${v.height}, ${v.frames} frames, ${v.durationMs} ms for a ${middle.durationMs} ms clip of a ${before.durationMs} ms project")
            assertEquals(w to h, v.width to v.height)
            assertEquals(720, minOf(v.width, v.height))
            assertTrue("only the clip (${v.durationMs} ms)", abs(v.durationMs - middle.durationMs) < 250)
            assertTrue("25 fps (${v.frames} frames)", abs(v.frames - middle.durationMs * 25 / 1000) <= 8)
            assertTrue(v.hasAudio)
        } finally {
            ctx.contentResolver.delete(uri!!, null, null)
        }
        // the project itself is untouched; the choice is remembered and shown
        assertEquals(before.clips.map { it.id to it.timelineStartMs }, editor.timeline.clips.map { it.id to it.timelineStartMs })
        rule.onNodeWithTag("exportBadge").assertTextContains("HD")
        assertEquals("720,25", ctx.getSharedPreferences("editor", 0).getString("export_settings", null))
        assertEquals("no player errors (${editor.player.lastError})", 0, editor.player.errors)
    }
}
