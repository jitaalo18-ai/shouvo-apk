package com.videoeditor.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.captions.AutoCaptioner
import com.videoeditor.app.captions.CaptionRenderer
import com.videoeditor.app.data.ClipJson
import com.videoeditor.app.data.clipFrom
import com.videoeditor.app.data.toEntity
import com.videoeditor.app.data.toClip
import com.videoeditor.app.media.Exporter
import com.videoeditor.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DynamicCaptionTest {
    private fun yellowX(b: Bitmap): Double {
        var sum = 0L; var n = 0
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val c = b.getPixel(x,y)
            if (Color.red(c) > 180 && Color.green(c) > 160 && Color.blue(c) < 90) { sum += x; n++ }
        }
        assertTrue("yellow word pixels exist", n > 15)
        return sum.toDouble()/n
    }
    @Test fun rendererUsesStableLayoutAndMovingYellowWord() {
        val first = CaptionRenderer.render("Hello world", "KARAOKE", .05f, 720, 1280, 0)
        val last = CaptionRenderer.render("Hello world", "KARAOKE", .05f, 720, 1280, 1)
        assertEquals(first.width, last.width); assertEquals(first.height, last.height)
        assertTrue(yellowX(last) > yellowX(first) + 50)
        assertEquals(145, Color.alpha(first.getPixel(0,0)))
        val bn = CaptionRenderer.render("আমার বাংলা ক্যাপশন", "KARAOKE", .05f, 720, 1280, 1)
        yellowX(bn)
        val dir = File(Fixtures.target.filesDir, "shots").apply { mkdirs() }
        File(dir, "dynamic-caption-en.png").outputStream().use { last.compress(Bitmap.CompressFormat.PNG,100,it) }
        File(dir, "dynamic-caption-bn.png").outputStream().use { bn.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun subwordTokensMergeWithoutBreakingBanglaAndTimingsPersist() {
        val p = AutoCaptioner.parse("1000\t2000\t আমি যাই\t0,4,1000,1200;4,10,1200,1400;10,20,1500,1900;")!!
        assertEquals(2, p.words.size)
        assertEquals(MsRange(0,400), p.words[0])
        assertEquals(MsRange(500,900), p.words[1])
        val c = Captions.build(listOf(p), 3000, { "c" }).single()
        assertEquals(c.captionTiming, ClipJson.decode(ClipJson.encode(listOf(c))).single().captionTiming)
        assertEquals(c.captionTiming, c.toEntity(1).toClip().captionTiming)
        assertTrue(AutoCaptioner.parse("0\t1000\tOld format")!!.words.isEmpty())
    }
    @Test fun exportMovesHighlightAtWordBoundaries() = runBlocking {
        val plain = TimelineOps.appendMain(Timeline(), listOf(clipFrom(Fixtures.info("portrait_test.mp4"), Track.MAIN)))
        val c = Captions.textClip("dynamic", 1000, 3000, "Hello world", style="KARAOKE", posY=.5f, size=.06f)
            .copy(captionTiming="0,1400;1500,2800")
        val uri = Exporter(Fixtures.target).export(Timeline(plain.clips+c), 9f/16f) { }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(Fixtures.target, uri)
            fun region(ms: Long): Bitmap {
                val b = r.getFrameAtTime(ms*1000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                return Bitmap.createBitmap(b, 0, (b.height*.43f).toInt(), b.width, (b.height*.14f).toInt())
            }
            val first = yellowX(region(1600)); val last = yellowX(region(3100))
            assertTrue("exported highlight moves right: $first -> $last", last > first+25)
            Report.note("Dynamic captions: exported yellow word moves $first -> $last px")
        } finally { r.release(); Fixtures.target.contentResolver.delete(uri,null,null) }
    }
}
