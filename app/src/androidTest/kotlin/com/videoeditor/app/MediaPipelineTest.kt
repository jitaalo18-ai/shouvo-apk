package com.videoeditor.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.data.clipFrom
import com.videoeditor.app.media.AutoCutEngine
import com.videoeditor.app.media.AutoCutOutcome
import com.videoeditor.app.media.Exporter
import com.videoeditor.app.media.MediaProbe
import com.videoeditor.app.media.PlaybackController
import com.videoeditor.app.media.Thumbnails
import com.videoeditor.app.media.Waveforms
import com.videoeditor.core.AutoCutStrength
import com.videoeditor.core.MediaKind
import com.videoeditor.core.MsRange
import com.videoeditor.core.Timeline
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Runs the real media code on Android with a synthetic 24 s test video whose sound has speech-like
 * parts at known times: 1.0-4.0, 6.5-9.0, 9.3-11.0, 15.0-19.0 and 21.5-23.0 s, faint noise elsewhere.
 */
@RunWith(AndroidJUnit4::class)
class MediaPipelineTest {
    private val ctx get() = Fixtures.target
    private val speech get() = Fixtures.uri("speech_test.mp4").toString()

    @Test fun probeTestVideo() {
        val info = Fixtures.info("speech_test.mp4")
        assertEquals(MediaKind.VIDEO, info.kind)
        assertTrue("duration ${info.durationMs}", abs(info.durationMs - 24_000) < 200)
        assertTrue(info.hasAudio)
        assertEquals(16f / 9f, info.aspect, 0.01f)
    }

    private fun diff(a: Bitmap, b: Bitmap): Double {
        var sum = 0.0
        var n = 0
        for (y in 0 until minOf(a.height, b.height) step 4) for (x in 0 until minOf(a.width, b.width) step 4) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            sum += abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) + abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))
            n += 3
        }
        return sum / n
    }

    @Test fun timelineThumbnailsLoadFastAndAreCached() = runBlocking {
        Thumbnails.clearMemory()
        File(ctx.cacheDir, "thumbs").deleteRecursively()
        val times = (0L until 24_000L step 2_000L).toList()
        val t0 = System.currentTimeMillis()
        val frames = times.map { t -> async { Thumbnails.load(ctx, speech, MediaKind.VIDEO, t) } }.awaitAll()
        val firstMs = System.currentTimeMillis() - t0
        Report.note("thumbnails: ${times.size} frames decoded in $firstMs ms")
        frames.forEachIndexed { i, b -> assertNotNull("frame at ${times[i]} ms", b) }
        assertTrue("first and middle frame look different", diff(frames[0]!!, frames[6]!!) > 3.0)
        assertTrue("decoding 12 thumbnails took $firstMs ms", firstMs < 20_000)
        times.forEach { assertNotNull(Thumbnails.peek(speech, MediaKind.VIDEO, it)) }
        assertNotNull("placeholder from a near frame", Thumbnails.nearest(speech, 7_100))
        // from the disk cache after the memory is cleared: no decoding at all
        Thumbnails.clearMemory()
        val decodedBefore = Thumbnails.decodedCount.value
        val t1 = System.currentTimeMillis()
        times.map { t -> async { Thumbnails.load(ctx, speech, MediaKind.VIDEO, t) } }.awaitAll().forEach { assertNotNull(it) }
        Report.note("thumbnails: ${times.size} frames from disk cache in ${System.currentTimeMillis() - t1} ms")
        assertEquals("disk cache used", decodedBefore, Thumbnails.decodedCount.value)
    }

    @Test fun thumbnailWorkIsKeptWhenTheTileScrollsAway() = runBlocking {
        Thumbnails.clearMemory()
        File(ctx.cacheDir, "thumbs").deleteRecursively()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch { Thumbnails.load(ctx, speech, MediaKind.VIDEO, 13_000) }
        delay(20)
        scope.cancel()                 // the tile left the screen
        var waited = 0
        while (Thumbnails.peek(speech, MediaKind.VIDEO, 13_000) == null && waited < 10_000) {
            delay(50)
            waited += 50
        }
        assertNotNull("frame finished and cached although its requester was cancelled", Thumbnails.peek(speech, MediaKind.VIDEO, 13_000))
    }

    @Test fun waveformShowsSpeechLouderThanPauses() = runBlocking {
        val t0 = System.currentTimeMillis()
        val peaks = Waveforms.load(ctx, speech)
        Report.note("waveform: ${System.currentTimeMillis() - t0} ms")
        assertNotNull(peaks)
        assertTrue("buckets ${peaks!!.size}", abs(peaks.size - 24_000 / 50) < 30)
        fun level(from: Long, to: Long) = (from / 50 until to / 50).map { peaks[it.toInt()].toInt() and 0xFF }.average()
        val speechLevel = level(1_200, 3_800)
        val pauseLevel = level(4_300, 6_200)
        assertTrue("speech $speechLevel vs pause $pauseLevel", speechLevel > pauseLevel * 2)
    }

    private fun near(a: MsRange, b: MsRange, tol: Long) = abs(a.startMs - b.startMs) <= tol && abs(a.endMs - b.endMs) <= tol

    @Test fun autoCutFindsThePausesOnDevice() = runBlocking {
        val clip = clipFrom(Fixtures.info("speech_test.mp4"), Track.MAIN)
        val t0 = System.currentTimeMillis()
        val outcome = AutoCutEngine.analyze(ctx, clip) { }
        Report.note("autocut analysis of 24 s: ${System.currentTimeMillis() - t0} ms")
        assertTrue("outcome $outcome", outcome is AutoCutOutcome.Ready)
        val plan = (outcome as AutoCutOutcome.Ready).planFor(AutoCutStrength.NORMAL)
        Report.note("autocut NORMAL keep=${plan.keep}")
        val expected = listOf(MsRange(850, 4_220), MsRange(6_350, 11_220), MsRange(14_850, 19_220), MsRange(21_350, 23_220))
        assertEquals("keep ranges ${plan.keep}", expected.size, plan.keep.size)
        expected.zip(plan.keep).forEach { (e, k) -> assertTrue("expected about $e, got $k", near(e, k, 250)) }
        // every speech part is fully inside a kept range
        listOf(MsRange(1_000, 4_000), MsRange(6_500, 11_000), MsRange(15_000, 19_000), MsRange(21_500, 23_000)).forEach { s ->
            assertTrue("speech $s kept", plan.keep.any { it.startMs <= s.startMs + 30 && it.endMs >= s.endMs - 30 })
        }
    }

    @Test fun exportAfterAutoCutHasTheRightLength() = runBlocking {
        val clip = clipFrom(Fixtures.info("speech_test.mp4"), Track.MAIN)
        val ready = AutoCutEngine.analyze(ctx, clip) { } as AutoCutOutcome.Ready
        val plan = ready.planFor(AutoCutStrength.NORMAL)
        var t = TimelineOps.appendMain(Timeline(), listOf(clip))
        t = TimelineOps.applyKeepRanges(t, clip.id, plan.keep, { java.util.UUID.randomUUID().toString() }).timeline
        val t0 = System.currentTimeMillis()
        val out = Exporter(ctx).export(t, 16f / 9f) { }
        Report.note("export of ${t.durationMs} ms timeline (${t.main.size} pieces): ${System.currentTimeMillis() - t0} ms")
        val info = MediaProbe.probe(ctx, out)
        try {
            assertNotNull(info)
            assertEquals(MediaKind.VIDEO, info!!.kind)
            assertTrue("exported ${info.durationMs} ms, expected ${t.durationMs} ms", abs(info.durationMs - t.durationMs) < 500)
            assertTrue("exported video has sound", info.hasAudio)
        } finally {
            ctx.contentResolver.delete(out, null, null)
        }
    }

    @Test fun playerFollowsEditsIncrementallyAndScrubsToTheLatestPosition() = runBlocking {
        withContext(Dispatchers.Main) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val player = PlaybackController(ctx, scope)
            try {
                val a = clipFrom(Fixtures.info("speech_test.mp4"), Track.MAIN)
                val b = clipFrom(Fixtures.info("portrait_test.mp4"), Track.MAIN)
                var t = TimelineOps.appendMain(Timeline(), listOf(a, b))
                player.setTimeline(t)
                delay(1_500)
                assertEquals(2, player.main.mediaItemCount)
                t = TimelineOps.split(t, a.id, 10_000, { "a2" }).timeline
                player.seekTo(20_000)
                delay(300)
                player.setTimeline(t)
                assertEquals(3, player.main.mediaItemCount)
                delay(800)
                assertTrue("position kept after split: ${player.positionMs.value}", abs(player.positionMs.value - 20_000) < 300)
                // drag: many changes, one update after the finger rests
                val before = player.playlistUpdates
                repeat(30) { i ->
                    t = TimelineOps.trimMain(t, "a2", newSourceEndMs = 24_000L - i * 50)
                    player.setTimeline(t, interactive = true)
                    delay(15)
                }
                delay(600)
                assertEquals("one playlist update for the whole drag", before + 1, player.playlistUpdates)
                // scrubbing: 40 quick positions, the last one wins
                val t0 = System.currentTimeMillis()
                for (i in 0 until 40) {
                    player.scrubTo(500L + i * 600)
                    delay(16)
                }
                player.endScrub()
                delay(1_200)
                Report.note("scrub: 40 moves in ${System.currentTimeMillis() - t0} ms, landed at ${player.positionMs.value}")
                assertTrue("landed at ${player.positionMs.value}", abs(player.positionMs.value - (500L + 39 * 600)) < 150)
            } finally {
                player.release()
                scope.cancel()
            }
        }
    }
}
