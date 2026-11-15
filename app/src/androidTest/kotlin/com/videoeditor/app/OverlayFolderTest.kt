package com.videoeditor.app

import android.net.Uri
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.data.*
import com.videoeditor.app.media.WebImages
import com.videoeditor.core.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OverlayFolderTest {
    @Test fun fiftyImagesAndVideoSurviveOriginalDeletionAndRepositoryReopen() = runBlocking {
        val ctx = Fixtures.target
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val repo = LibraryRepository(ctx, db)
        val image = File(ctx.cacheDir, "original_${UUID.randomUUID()}.png").apply { Fixtures.file("image_test.png").copyTo(this) }
        val video = File(ctx.cacheDir, "original_${UUID.randomUUID()}.mp4").apply { Fixtures.file("portrait_test.mp4").copyTo(this) }
        val bytes = image.readBytes()
        val records = repo.importItems(LibraryKind.OVERLAYS, List(50) { Uri.fromFile(image) } + Uri.fromFile(video))
        try {
            assertEquals(51, records.size)
            assertEquals(51, records.map { it.fileName }.toSet().size)
            assertTrue(image.delete()); assertTrue(video.delete())
            val reopened = LibraryRepository(ctx, db)
            assertEquals(51, reopened.observe(LibraryKind.OVERLAYS).first().size)
            assertEquals(50, records.count { reopened.mediaKind(it) == MediaKind.IMAGE })
            for (item in records.filter { reopened.mediaKind(it) == MediaKind.IMAGE }) {
                assertArrayEquals(bytes, reopened.file(item).readBytes())
                assertNotNull(WebImages.preview(ctx, Uri.fromFile(reopened.file(item)), 128))
                assertEquals(MediaKind.IMAGE, clipFrom(reopened.toMediaInfo(item), Track.OVERLAY).kind)
            }
            assertEquals(MediaKind.VIDEO, reopened.toMediaInfo(records.last()).kind)
            assertTrue(reopened.observe(LibraryKind.VIDEO_FX).first().isEmpty())
            val item = records.first()
            reopened.rename(item.id, "My logo")
            assertEquals("My logo", reopened.observe(LibraryKind.OVERLAYS).first().first { it.id == item.id }.name)
            reopened.remove(item.id)
            assertFalse(reopened.file(item).exists())
        } finally { records.forEach { repo.remove(it.id) }; image.delete(); video.delete(); db.close() }
    }

    @Test fun removedOverlayRemainsAvailableToSavedProjectUntilProjectIsDeleted() = runBlocking {
        val ctx = Fixtures.target
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val library = LibraryRepository(ctx, db)
        val projects = ProjectRepository(db)
        val item = library.importItems(LibraryKind.OVERLAYS, listOf(Fixtures.uri("image_test.png"))).single()
        try {
            val id = projects.create(listOf(Fixtures.info("portrait_test.mp4")))
            val overlay = clipFrom(library.toMediaInfo(item), Track.OVERLAY, libraryItemId = item.id)
            projects.save(id, Timeline(projects.load(id).clips + overlay))
            assertEquals(1, library.checkRemove(item.id)!!.usedInProjects.size)
            library.remove(item.id)
            assertTrue(library.observe(LibraryKind.OVERLAYS).first().isEmpty())
            assertTrue(library.file(item).isFile)
            assertTrue(projects.load(id).clips.any { it.libraryItemId == item.id })
            projects.delete(id); library.cleanUpRemoved()
            assertFalse(library.file(item).exists())
        } finally { library.remove(item.id); db.close() }
    }

    @Test fun webImagesValidateBytesAndKeepTransparency() = runBlocking {
        val ctx = Fixtures.target
        val bitmap = android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixel(3,3,android.graphics.Color.RED)
        val buffer = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,buffer)
        val file = WebImages.download(ctx, "data:image/png;base64,"+Base64.encodeToString(buffer.toByteArray(),Base64.NO_WRAP))
        try {
            val preview = WebImages.preview(ctx, Uri.fromFile(file))!!
            assertEquals(0, android.graphics.Color.alpha(preview.getPixel(0,0)))
            assertEquals(android.graphics.Color.RED, preview.getPixel(3,3))
        } finally { file.delete(); bitmap.recycle() }
        for (bad in listOf("file:///etc/passwd", "javascript:alert(1)", "data:image/png;base64,aGVsbG8=")) {
            try { WebImages.download(ctx,bad); fail("Invalid image should be rejected") } catch (_: Exception) { }
        }
        assertTrue(WebImages.searchUrl("Tom Cruise & movies",true).contains("Tom%20Cruise%20%26%20movies"))
    }
}
