package com.videoeditor.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.captions.AutoCaptioner
import com.videoeditor.app.captions.CaptionLanguage
import com.videoeditor.app.captions.CaptionModel
import com.videoeditor.app.captions.CaptionModels
import com.videoeditor.app.captions.SpeechAudio
import com.videoeditor.app.captions.WhisperNative
import com.videoeditor.app.data.clipFrom
import com.videoeditor.app.media.Exporter
import com.videoeditor.app.media.MediaProbe
import com.videoeditor.core.Captions
import com.videoeditor.core.MediaKind
import com.videoeditor.core.Timeline
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.math.abs

/** Auto Captions on a device: audio, speech recognition (English and Bangla), burn-in on export. */
@RunWith(AndroidJUnit4::class)
class CaptionsTest {
    private val ctx get() = Fixtures.target
    private fun id() = UUID.randomUUID().toString()

    private fun model(m: CaptionModel): Boolean {
        // the voice-activity model comes with every model download in the app
        Pushed.copyTo(CaptionModel.VAD_FILE, CaptionModels.vadFile(ctx))
        return CaptionModels.isReady(ctx, m) || Pushed.copyTo(m.fileName, CaptionModels.file(ctx, m))
    }

    @Test fun englishSpeechBecomesTimedCaptions() = runBlocking {
        assertTrue("speech library loaded: ${WhisperNative.available}", WhisperNative.available)
        Report.note("whisper: ${WhisperNative.systemInfo()}")
        assertTrue("small model pushed by CI", model(CaptionModel.SMALL))
        val video = Pushed.file("speech_en.mp4")
        assertNotNull("English speech pushed by CI", video)
        val info = MediaProbe.probe(ctx, Uri.fromFile(video))!!
        val clip = clipFrom(info, Track.MAIN)
        val t = TimelineOps.appendMain(Timeline(), listOf(clip))

        val audio = SpeechAudio.timeline(ctx, t.main) { }
        assertTrue("16 kHz samples for the whole clip", abs(audio.size - t.durationMs * 16) < 1_600)
        fun loud(fromMs: Int, toMs: Int) = (fromMs * 16 until toMs * 16).maxOf { abs(audio[it]) }
        assertTrue("silent first second", loud(0, 1_200) < 0.02f)
        assertTrue("speech after 1.5 s", loud(1_600, 4_000) > 0.05f)

        val t0 = System.currentTimeMillis()
        val pieces = AutoCaptioner.recognise(ctx, t.main, CaptionLanguage.ENGLISH) { _, _ -> }
        val spent = System.currentTimeMillis() - t0
        val heard = pieces.joinToString(" ") { it.text }.lowercase()
        Report.note("captions: English ${t.durationMs} ms of audio in $spent ms -> ${pieces.size} pieces: $heard")
        for (word in listOf("everyone", "video", "editor", "clips", "export")) {
            assertTrue("heard \"$word\" in: $heard", heard.contains(word))
        }
        val first = pieces.first()
        Report.note("captions: first piece ${first.startMs}-${first.endMs} ms \"${first.text}\"")
        assertTrue("first caption starts with the speech (1.5 s), was ${first.startMs}", first.startMs in 1_000..2_600)
        val clips = Captions.build(pieces, t.durationMs, ::id)
        assertTrue(clips.size >= 3)
        for (i in 1 until clips.size) assertTrue(clips[i].timelineStartMs >= clips[i - 1].timelineEndMs)
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\u0964\\u0965.,!?;:\"'()\\-]"), " ").replace(Regex("\\s+"), "")

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    @Test fun banglaModelRecognisesRealBanglaSpeech() = runBlocking {
        assertTrue("Bangla model pushed by CI", model(CaptionModel.BANGLA_TURBO))
        val wav = Pushed.file("bn_clip.wav")
        val truth = Pushed.file("bn_clip.txt")?.readText()?.trim()
        assertNotNull("Bangla FLEURS clip pushed by CI", wav)
        val info = MediaProbe.probe(ctx, Uri.fromFile(wav))!!
        val clip = clipFrom(info, Track.MAIN)
        val t = TimelineOps.appendMain(Timeline(), listOf(clip))
        val t0 = System.currentTimeMillis()
        val pieces = AutoCaptioner.recognise(ctx, t.main, CaptionLanguage.BANGLA) { _, _ -> }
        val spent = System.currentTimeMillis() - t0
        val heard = pieces.joinToString(" ") { it.text }
        val cer = distance(norm(heard), norm(truth!!)).toFloat() / norm(truth).length
        Report.note("captions: Bangla ${t.durationMs} ms in $spent ms (emulator CPU), CER ${"%.1f".format(cer * 100)}%")
        Report.note("captions: Bangla heard: $heard")
        Report.note("captions: Bangla truth: $truth")
        assertTrue("Bangla letters recognised", heard.any { it in 'ঀ'..'৿' })
        assertTrue("character error rate ${cer * 100}% under 30%", cer < 0.30f)
    }

    @Test fun longBanglaSpeechIsCaptionedCompletely() = runBlocking {
        assertTrue("Bangla model pushed by CI", model(CaptionModel.BANGLA_TURBO))
        val wav = Pushed.file("bn_long.wav")
        val truth = Pushed.file("bn_long.txt")?.readText()?.trim()
        assertNotNull("long Bangla passage pushed by CI", wav)
        val clip = clipFrom(MediaProbe.probe(ctx, Uri.fromFile(wav))!!, Track.MAIN)
        val t = TimelineOps.appendMain(Timeline(), listOf(clip))
        val t0 = System.currentTimeMillis()
        val pieces = AutoCaptioner.recognise(ctx, t.main, CaptionLanguage.BANGLA) { _, _ -> }
        val spent = System.currentTimeMillis() - t0
        val heard = pieces.joinToString(" ") { it.text }
        val cer = distance(norm(heard), norm(truth!!)).toFloat() / norm(truth).length
        val covered = norm(heard).length.toFloat() / norm(truth).length
        Report.note("captions: long Bangla ${t.durationMs} ms in $spent ms (emulator CPU), ${pieces.size} pieces, CER ${"%.1f".format(cer * 100)}%, length ${"%.0f".format(covered * 100)}% of the truth")
        Report.note("captions: long Bangla heard: $heard")
        assertTrue("nothing cut off: ${covered * 100}% of the text", covered > 0.85f)
        assertTrue("character error rate ${cer * 100}% under 30%", cer < 0.30f)
        val last = pieces.last()
        assertTrue("captions reach the end of the speech (${last.endMs} of ${t.durationMs})", last.endMs > t.durationMs - 3_000)
    }

    private fun frame(uri: Uri, ms: Long): Bitmap {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            return r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        } finally {
            r.release()
        }
    }

    /** Mean brightness difference of two frames inside a box (fractions of the frame). */
    private fun diff(a: Bitmap, b: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float): Double {
        var sum = 0.0
        var n = 0
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        var y = (y0 * h).toInt()
        while (y < (y1 * h).toInt()) {
            var x = (x0 * w).toInt()
            while (x < (x1 * w).toInt()) {
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                sum += abs(((pa shr 16) and 255) + ((pa shr 8) and 255) + (pa and 255) - ((pb shr 16) and 255) - ((pb shr 8) and 255) - (pb and 255)) / 3.0
                n++
                x += 3
            }
            y += 3
        }
        return sum / n.coerceAtLeast(1)
    }

    @Test fun exportBurnsCaptionsIntoTheVideo() = runBlocking {
        val plain = TimelineOps.appendMain(Timeline(), listOf(clipFrom(Fixtures.info("portrait_test.mp4"), Track.MAIN)))
        val caption = Captions.textClip(id(), 2_000, 2_000, "Hello নমস্কার", style = "BOX", posY = 0.5f, size = 0.08f)
        val withText = Timeline(plain.clips + caption)
        val a = Exporter(ctx).export(plain, 9f / 16f) { }
        val b = Exporter(ctx).export(withText, 9f / 16f) { }
        try {
            val inside = diff(frame(a, 3_000), frame(b, 3_000), 0.2f, 0.45f, 0.8f, 0.55f)
            val outside = diff(frame(a, 3_000), frame(b, 3_000), 0.1f, 0.05f, 0.9f, 0.25f)
            val before = diff(frame(a, 1_000), frame(b, 1_000), 0.2f, 0.45f, 0.8f, 0.55f)
            Report.note("captions: export difference where the caption is ${"%.1f".format(inside)}, elsewhere ${"%.1f".format(outside)}, before it shows ${"%.1f".format(before)}")
            assertTrue("caption visible in the exported video ($inside)", inside > 25)
            assertTrue("rest of the picture unchanged ($outside)", outside < 8)
            assertTrue("not shown before its time ($before)", before < 8)
        } finally {
            ctx.contentResolver.delete(a, null, null)
            ctx.contentResolver.delete(b, null, null)
        }
    }
}
