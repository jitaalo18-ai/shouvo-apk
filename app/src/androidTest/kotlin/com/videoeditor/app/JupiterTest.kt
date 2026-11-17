package com.videoeditor.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.media.SoundRemover
import com.videoeditor.app.ui.JupiterTest as Picks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Jupiter (like the BlackHole app): silent copies of videos in the Gallery. The picture must be
 * the very same bytes (copied, not encoded again), as long as before, turned the same way; the
 * sound gone; the original untouched; nothing half-written left behind when stopped.
 */
@RunWith(AndroidJUnit4::class)
class JupiterTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ctx get() = Fixtures.target
    private val made = ArrayList<Uri>()

    @After fun cleanUp() {
        SoundRemover.forceReencode = false
        SoundRemover.testDelayPerSampleMs = 0
        Picks.picks = null
        for (u in made) try {
            ctx.contentResolver.delete(u, null, null)
        } catch (_: Exception) {
        }
        // anything else this test left in Movies/Jupiter (also half-written files)
        for (u in jupiterFiles()) ctx.contentResolver.delete(u.first, null, null)
    }

    /** Gallery entries in Movies/Jupiter (pending ones too): uri, name, pending. */
    private fun jupiterFiles(): List<Triple<Uri, String, Boolean>> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("Movies/Jupiter%"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val out = ArrayList<Triple<Uri, String, Boolean>>()
        ctx.contentResolver.query(
            collection, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.IS_PENDING), args, null,
        )?.use { c ->
            while (c.moveToNext()) out += Triple(android.content.ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(1), c.getInt(2) == 1)
        }
        return out
    }

    private class Track(val mime: String, val samples: List<Pair<Long, Int>>, val firstBytes: List<ByteArray>, val durationUs: Long)

    /** The tracks of a file: their kind, sample times / sizes, and the bytes of the first samples. */
    private fun tracks(uri: Uri): List<Track> {
        val e = MediaExtractor()
        try {
            e.setDataSource(ctx, uri, null)
            return (0 until e.trackCount).map { t ->
                val f = e.getTrackFormat(t)
                e.selectTrack(t)
                e.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val samples = ArrayList<Pair<Long, Int>>()
                val bytes = ArrayList<ByteArray>()
                val buf = ByteBuffer.allocate(4 * 1024 * 1024)
                while (e.sampleTrackIndex >= 0) {
                    buf.clear()
                    val n = e.readSampleData(buf, 0)
                    samples += e.sampleTime to n
                    if (bytes.size < 20) bytes += ByteArray(n).also { buf.get(it, 0, n) }
                    e.advance()
                }
                e.unselectTrack(t)
                Track(f.getString(MediaFormat.KEY_MIME)!!, samples, bytes, if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0)
            }
        } finally {
            e.release()
        }
    }

    private fun rotation(uri: Uri): Int {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally {
            r.release()
        }
    }

    @Test fun silentCopyHasTheSamePictureAndNoSound() = runBlocking {
        for (name in listOf("noisy_test.mp4", "rotated_test.mp4", "phone_test.mp4")) {
            val src = Fixtures.uri(name)
            val before = Fixtures.file(name).let { it.length() to it.lastModified() }
            val t0 = System.currentTimeMillis()
            var progressed = 0f
            val saved = SoundRemover.removeSound(ctx, src) { p ->
                assertTrue(p >= progressed)
                progressed = p
            }
            made += saved.uri
            val inT = tracks(src)
            val outT = tracks(saved.uri)
            val inV = inT.single { it.mime.startsWith("video/") }
            val outV = outT.single()
            Report.note(
                "jupiter: $name -> ${saved.name} in ${System.currentTimeMillis() - t0} ms; tracks in ${inT.map { it.mime }} out ${outT.map { it.mime }}; " +
                    "${outV.samples.size} of ${inV.samples.size} picture samples; rotation ${rotation(src)} -> ${rotation(saved.uri)}; re-encoded ${saved.reencoded}",
            )
            assertTrue("the source had sound", inT.any { it.mime.startsWith("audio/") })
            assertTrue("no sound in the copy", outT.none { it.mime.startsWith("audio/") })
            assertFalse("copied, not encoded again", saved.reencoded)
            assertEquals(inV.mime, outV.mime)
            assertEquals("every picture sample", inV.samples.size, outV.samples.size)
            assertEquals("same sizes and times", inV.samples, outV.samples)
            for (i in inV.firstBytes.indices) assertTrue("sample $i is the same bytes", inV.firstBytes[i].contentEquals(outV.firstBytes[i]))
            assertEquals("turned the same way", rotation(src), rotation(saved.uri))
            assertTrue(saved.name.endsWith("_no_sound.mp4"))
            assertEquals(1f, progressed)
            assertEquals("the original is untouched", before, Fixtures.file(name).let { it.length() to it.lastModified() })
            if (name == "rotated_test.mp4") assertTrue("the test video really is turned", rotation(src) != 0)
        }
        val listed = jupiterFiles()
        assertEquals("3 finished copies in Movies/Jupiter", 3, listed.count { !it.third })

        // a video without sound: told, nothing saved
        try {
            SoundRemover.removeSound(ctx, made.first())
            fail("a silent video was copied again")
        } catch (e: SoundRemover.Failed) {
            assertEquals("This video already has no sound.", e.message)
        }
        assertEquals(3, jupiterFiles().size)

        // the other way (if a phone cannot copy a kind of video): encoded again, still right
        SoundRemover.forceReencode = true
        val r = SoundRemover.removeSound(ctx, Fixtures.uri("scenes_test.mp4"))
        made += r.uri
        val v = tracks(r.uri)
        val src = tracks(Fixtures.uri("scenes_test.mp4")).single { it.mime.startsWith("video/") }
        Report.note("jupiter re-encode: ${v.map { it.mime }}, ${v.single().samples.size} of ${src.samples.size} picture samples, duration ${v.single().durationUs / 1000} ms of ${src.durationUs / 1000}")
        assertTrue(r.reencoded)
        assertTrue(v.none { it.mime.startsWith("audio/") })
        assertTrue(abs(v.single().durationUs - src.durationUs) < 150_000)
        assertTrue(abs(v.single().samples.size - src.samples.size) <= 3)
    }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) { condition() }
        } catch (e: Throwable) {
            Shots.take("FAILED_$what")
            throw AssertionError("timed out waiting for: $what", e)
        }
    }

    private fun count(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun jupiterButtonOnTheProjectsScreenRemovesTheSound() {
        waitFor("projects screen") { count("jupiterButton") == 1 }
        rule.waitForIdle()
        Thread.sleep(800)
        Shots.take("98_jupiter_button")
        rule.onNodeWithTag("jupiterButton").performClick()
        waitFor("jupiter screen") { count("jupiterScreen") == 1 }
        rule.waitForIdle()
        Thread.sleep(600)
        Shots.take("99_jupiter_empty")

        Picks.picks = listOf(Fixtures.uri("noisy_test.mp4"), Fixtures.uri("rotated_test.mp4"))
        rule.onNodeWithTag("jupiterChoose").performClick()
        waitFor("two videos listed") { count("jupiterItem") == 2 }
        rule.onNodeWithTag("jupiterRemove").performClick()
        waitFor("both saved", 60_000) { count("jupiterSaved") == 2 }
        waitFor("note") { count("jupiterNote") == 1 }
        rule.waitForIdle()
        Thread.sleep(800)
        Shots.take("99_jupiter_done")
        val files = jupiterFiles()
        made += files.map { it.first }
        assertEquals(listOf(false, false), files.map { it.third })
        assertEquals(setOf("noisy_test_no_sound.mp4", "rotated_test_no_sound.mp4"), files.map { it.second }.toSet())
        for (f in files) assertTrue(tracks(f.first).none { it.mime.startsWith("audio/") })

        // stopped half way: nothing half-written is left (600 samples x 20 ms = 12 s of copying)
        SoundRemover.testDelayPerSampleMs = 20
        Picks.picks = listOf(Fixtures.uri("phone_test.mp4"))
        rule.onNodeWithTag("jupiterChoose").performClick()
        waitFor("third listed") { count("jupiterItem") == 3 }
        rule.onNodeWithTag("jupiterRemove").performClick()
        waitFor("copying") { rule.onAllNodes(androidx.compose.ui.test.hasText("Removing the sound…", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1_500)
        assertTrue("a half-written copy exists while it works", jupiterFiles().any { it.third })
        rule.onNodeWithTag("jupiterRemove").performClick()          // Stop
        waitFor("stopped") { rule.onAllNodes(androidx.compose.ui.test.hasText("Stopped", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1_000)
        val after = jupiterFiles()
        Report.note("jupiter stop: Gallery has ${after.map { it.second to it.third }}")
        assertEquals("only the two finished copies", 2, after.size)
        assertTrue(after.none { it.third })
    }
}
