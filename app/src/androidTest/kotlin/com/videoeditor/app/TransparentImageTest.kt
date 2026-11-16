package com.videoeditor.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.data.LibraryKind
import com.videoeditor.app.data.clipFrom
import com.videoeditor.app.media.BackgroundRemover
import com.videoeditor.app.media.Exporter
import com.videoeditor.app.media.MediaProbe
import com.videoeditor.app.ui.AppTheme
import com.videoeditor.app.ui.LibraryDialog
import com.videoeditor.core.Timeline
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * "Transparent image" in the Overlay Folder: the button makes a copy of a picture without its
 * background (a PNG with a see-through background) and keeps the original; the preview is a
 * square with Insert right under it; the see-through parts really let the video through in an
 * exported video; and Google's on-device subject finder really cuts a person out of a photo.
 */
@RunWith(AndroidJUnit4::class)
class TransparentImageTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = Fixtures.target

    @After fun realCutter() {
        BackgroundRemover.cutter = null
    }

    /** Keeps a disc in the middle, the rest becomes transparent (stands in for the subject finder). */
    private val discCutter: suspend (android.content.Context, Bitmap, (String) -> Unit) -> Bitmap = { _, src, _ ->
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val r = minOf(src.width, src.height) / 4f
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val dx = x - src.width / 2f
            val dy = y - src.height / 2f
            if (dx * dx + dy * dy <= r * r) out.setPixel(x, y, src.getPixel(x, y) or (0xFF shl 24))
        }
        out
    }

    @Test fun transparentButtonMakesACopyWithoutBackgroundAndPreviewIsSquare() {
        val app = ctx.applicationContext as App
        val original = runBlocking {
            app.library.importItems(LibraryKind.OVERLAYS, listOf(Fixtures.uri("image_test.png"))).single().also {
                app.library.rename(it.id, "Cutout source")
            }
        }
        BackgroundRemover.cutter = discCutter
        var made: com.videoeditor.app.data.LibraryItemEntity? = null
        try {
            rule.setContent { AppTheme { LibraryDialog(app, LibraryKind.OVERLAYS, {}, {}, {}, {}) } }
            rule.onNodeWithText("Search by name").performTextInput("Cutout source")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("transparentImage").fetchSemanticsNodes().size == 1 }
            rule.onNodeWithContentDescription("Transparent image", useUnmergedTree = true).assertExists()
            rule.onNodeWithTag("transparentImage").performClick()
            rule.waitUntil(30_000) { rule.onAllNodesWithTag("transparentMessage").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Saved \"Cutout source transparent\" - the original is kept.").assertExists()
            rule.onNodeWithText("OK").performClick()

            val items = runBlocking { app.library.observe(LibraryKind.OVERLAYS).first() }
            assertTrue("original kept", items.any { it.id == original.id })
            made = items.single { it.name == "Cutout source transparent" }
            val file = app.library.file(made!!)
            val png = BitmapFactory.decodeFile(file.absolutePath)
            val src = BitmapFactory.decodeFile(app.library.file(original).absolutePath)
            assertEquals(src.width, png.width)
            assertEquals(src.height, png.height)
            assertTrue("PNG with alpha", png.hasAlpha())
            assertEquals("corner is see-through", 0, Color.alpha(png.getPixel(2, 2)))
            assertEquals("the subject is solid", 255, Color.alpha(png.getPixel(png.width / 2, png.height / 2)))
            assertEquals("the subject keeps its colours", src.getPixel(src.width / 2, src.height / 2), png.getPixel(png.width / 2, png.height / 2))

            // both are listed; the new one opens in a square preview with Insert right below it
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("transparentImage").fetchSemanticsNodes().size == 2 }
            rule.onAllNodesWithContentDescription("Preview image")[0].performClick()
            rule.onNodeWithTag("savedImagePreview").assertExists()
            val box = rule.onNodeWithTag("savedImageBox").getUnclippedBoundsInRoot()
            assertTrue("1:1 preview (${box.width} x ${box.height})", abs((box.width - box.height).value) < 1f)
            rule.onNodeWithTag("insertOverlay").assertIsDisplayed()
            val button = rule.onNodeWithTag("insertOverlay").getUnclippedBoundsInRoot()
            assertTrue("Insert is right under the picture", (button.top - box.bottom).value in 0f..40f)
            rule.waitForIdle()
            android.os.SystemClock.sleep(1500)
            Shots.take("transparent_preview")
            rule.onNodeWithText("Close preview").performClick()
            rule.waitForIdle()
            android.os.SystemClock.sleep(800)
            Shots.take("transparent_folder")
        } finally {
            runBlocking {
                app.library.remove(original.id)
                made?.let { app.library.remove(it.id) }
            }
        }
    }

    @Test fun failuresAreToldAndNothingIsAdded() {
        val app = ctx.applicationContext as App
        val original = runBlocking {
            app.library.importItems(LibraryKind.OVERLAYS, listOf(Fixtures.uri("image_test.png"))).single().also { app.library.rename(it.id, "Empty cutout") }
        }
        // nothing found: everything transparent
        BackgroundRemover.cutter = { _, src, _ -> Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888) }
        try {
            val before = runBlocking { app.library.observe(LibraryKind.OVERLAYS).first().size }
            rule.setContent { AppTheme { LibraryDialog(app, LibraryKind.OVERLAYS, {}, {}, {}, {}) } }
            rule.onNodeWithText("Search by name").performTextInput("Empty cutout")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("transparentImage").fetchSemanticsNodes().size == 1 }
            rule.onNodeWithTag("transparentImage").performClick()
            rule.waitUntil(30_000) { rule.onAllNodesWithText("No subject was found in this picture.").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, runBlocking { app.library.observe(LibraryKind.OVERLAYS).first().size })
            assertEquals("no leftovers", 0, ctx.cacheDir.listFiles { f -> f.name.startsWith("transparent_") }!!.size)
        } finally {
            runBlocking { app.library.remove(original.id) }
        }
    }

    private fun frame(uri: Uri, ms: Long): Bitmap {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            return r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            r.release()
        }
    }

    private fun isRed(c: Int) = Color.red(c) > 170 && Color.green(c) < 90 && Color.blue(c) < 90

    @Test fun seeThroughPartsShowTheVideoInTheExport() = runBlocking {
        // an overlay picture: left half see-through, right half red
        val png = File(ctx.cacheDir, "half_${System.nanoTime()}.png")
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawRect(200f, 0f, 400f, 400f, Paint().apply { color = Color.RED })
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val main = clipFrom(Fixtures.info("portrait_test.mp4"), Track.MAIN)
        val plain = TimelineOps.appendMain(Timeline(), listOf(main))
        val overlay = clipFrom(MediaProbe.probe(ctx, Uri.fromFile(png))!!, Track.OVERLAY, 0)
        val withOverlay = TimelineOps.addClip(plain, overlay)
        val a = Exporter(ctx).export(plain, 9f / 16f) { }
        val b = Exporter(ctx).export(withOverlay, 9f / 16f) { }
        try {
            val fa = frame(a, 1_000)
            val fb = frame(b, 1_000)
            // where the red half landed
            var x0 = Int.MAX_VALUE; var x1 = -1; var y0 = Int.MAX_VALUE; var y1 = -1
            for (y in 0 until fb.height step 2) for (x in 0 until fb.width step 2) {
                if (isRed(fb.getPixel(x, y)) && !isRed(fa.getPixel(x, y))) {
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                }
            }
            assertTrue("the red half is in the video", x1 > x0 && y1 > y0)
            val w = x1 - x0
            val h = y1 - y0
            assertTrue("it is the right half of a square (${w} x ${h})", abs(h - 2 * w) < h / 5)
            // the see-through half, left of it: the video as it is without the overlay
            var same = 0
            var total = 0
            for (y in y0 + h / 8 until y1 - h / 8 step 4) for (x in maxOf(0, x0 - w + w / 8) until x0 - w / 8 step 4) {
                val p = fa.getPixel(x, y)
                val q = fb.getPixel(x, y)
                total++
                if (abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q)) < 60) same++
            }
            Report.note("transparent export: red half ${w}x${h} px; in the see-through half $same of $total sampled pixels show the video unchanged")
            assertTrue(total > 50)
            assertTrue("the video shows through ($same of $total)", same > total * 9 / 10)
        } finally {
            ctx.contentResolver.delete(a, null, null)
            ctx.contentResolver.delete(b, null, null)
            png.delete()
        }
    }

    /** The real thing: Google's subject finder on a photo of a person (public-domain portrait). */
    @Test fun mlKitCutsAPersonOutOfAPhoto() = runBlocking {
        val photo = Fixtures.file("subject_test.jpg")
        val stages = ArrayList<String>()
        val t0 = System.currentTimeMillis()
        val out = try {
            BackgroundRemover.removeBackground(ctx, photo) { stages += it }
        } catch (e: BackgroundRemover.Failed) {
            Report.note("transparent ML Kit: NOT RUN on this emulator - ${e.message} (stages $stages)")
            assumeTrue("subject finder not available on this emulator: ${e.message}", false)
            return@runBlocking
        }
        try {
            val png = BitmapFactory.decodeFile(out.absolutePath)
            assertNotNull(png)
            var clear = 0
            var solid = 0
            for (y in 0 until png.height step 4) for (x in 0 until png.width step 4) {
                val al = Color.alpha(png.getPixel(x, y))
                if (al < 20) clear++ else if (al > 235) solid++
            }
            val total = (png.height / 4) * (png.width / 4)
            Report.note("transparent ML Kit: ${png.width}x${png.height} in ${System.currentTimeMillis() - t0} ms; see-through ${100 * clear / total}%, solid ${100 * solid / total}%; stages $stages")
            // shown on a green card for the report
            val card = Bitmap.createBitmap(png.width, png.height, Bitmap.Config.ARGB_8888)
            Canvas(card).apply { drawColor(Color.rgb(0, 200, 0)); drawBitmap(png, 0f, 0f, null) }
            File(File(ctx.filesDir, "shots").apply { mkdirs() }, "transparent_mlkit.png").outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(512, png.width)
            assertTrue("the top corners (background) are see-through", Color.alpha(png.getPixel(6, 6)) < 30 && Color.alpha(png.getPixel(png.width - 7, 6)) < 30)
            assertTrue("the face is solid", Color.alpha(png.getPixel(png.width / 2, png.height * 3 / 10)) > 230)
            assertTrue("a real cut-out: part see-through, part solid", clear > total / 10 && solid > total / 5)
        } finally {
            out.delete()
        }
    }
}
