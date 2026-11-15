package com.videoeditor.app

import android.media.MediaExtractor
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.data.clipFrom
import com.videoeditor.app.media.*
import com.videoeditor.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class LargeExportTest {
    private val ctx get() = Fixtures.target
    private fun digestVideo(uri: Uri): Pair<Long, String> {
        val e = MediaExtractor()
        try {
            e.setDataSource(ctx, uri, null)
            val index = (0 until e.trackCount).first { e.getTrackFormat(it).getString("mime")!!.startsWith("video/") }
            e.selectTrack(index)
            val hash = MessageDigest.getInstance("SHA-256")
            var buffer = ByteBuffer.allocate(1024 * 1024)
            var samples = 0L
            while (e.sampleTime >= 0) {
                val size = e.sampleSize.toInt()
                if (size > buffer.capacity()) buffer = ByteBuffer.allocate(size)
                buffer.clear()
                val read = e.readSampleData(buffer, 0)
                hash.update(buffer.array(), 0, read)
                samples++
                e.advance()
            }
            return samples to hash.digest().joinToString("") { "%02x".format(it) }
        } finally { e.release() }
    }

    @Test fun isolatedOneGiBVideoExportsWithIdenticalPictureAndRestoresPreview() = runBlocking {
        val file = Pushed.file("large_export.mp4") ?: error("Run ci/large-export-fixture.sh before tests")
        try {
            assertTrue(file.length() >= 1024L * 1024 * 1024)
            val info = MediaProbe.probe(ctx, Uri.fromFile(file))!!
            val c = clipFrom(info, Track.MAIN).copy(audio=AudioFx(isolate=true))
            val timeline = Timeline(listOf(c))
            assertTrue(ExportPolicy.canCopyVideo(timeline, c.aspect))
            assertNotNull(VoiceProcessing.ensureIsolation(ctx, c.sourceUri))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val player = withContext(Dispatchers.Main) { PlaybackController(ctx, scope).also { it.setTimeline(timeline) } }
            var output: Uri? = null
            try {
                withContext(Dispatchers.Main) {
                    player.seekTo(2_000)
                    player.suspendForExport()
                    assertEquals(0, player.main.mediaItemCount)
                }
                val before = digestVideo(Uri.fromFile(file))
                val start = System.currentTimeMillis()
                output = Exporter(ctx).export(timeline, c.aspect) {}
                val actual = MediaProbe.probe(ctx, output!!)!!
                assertTrue(actual.hasAudio)
                assertTrue(kotlin.math.abs(info.durationMs - actual.durationMs) < 150)
                assertEquals(before, digestVideo(output!!))
                Report.note("1 GiB isolation export: input=${file.length()}, duration=${actual.durationMs}, elapsed=${System.currentTimeMillis()-start} ms; every encoded video sample is identical")
                withContext(Dispatchers.Main) {
                    player.resumeAfterExport(timeline)
                    assertEquals(1, player.main.mediaItemCount)
                    assertEquals(2_000L, player.positionMs.value)
                }
            } finally {
                output?.let { ctx.contentResolver.delete(it, null, null) }
                withContext(Dispatchers.Main) { player.release() }
                scope.cancel()
            }
        } finally { file.delete() }
    }
}
