package com.videoeditor.app

import com.videoeditor.app.ui.AppTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.data.LibraryKind
import com.videoeditor.app.ui.LibraryDialog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayFolderUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun imagePreviewAndInsertAreSeparateActions() {
        val app = Fixtures.target.applicationContext as App
        val item = runBlocking {
            app.library.importItems(LibraryKind.OVERLAYS, listOf(Fixtures.uri("image_test.png"))).single().also {
                app.library.rename(it.id, "Overlay UI image")
            }
        }
        var inserted: Long? = null
        var searched = false
        try {
            rule.setContent { AppTheme {
                LibraryDialog(app, LibraryKind.OVERLAYS, {}, {}, { inserted = it.id }, { searched = true })
            } }
            rule.onNodeWithText("Search by name").performTextInput("Overlay UI image")
            rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("Preview image").fetchSemanticsNodes().size == 1 }
            rule.onNodeWithContentDescription("Preview image").performClick()
            rule.onNodeWithTag("savedImagePreview").assertExists()
            assertNull(inserted)
            rule.onNodeWithText("Close preview").performClick()
            rule.onNodeWithTag("overlayImageSearch").performClick()
            assertTrue(searched)
            rule.onNodeWithContentDescription("Insert Overlay UI image").performClick()
            assertEquals(item.id, inserted)
            rule.waitForIdle()
            android.os.SystemClock.sleep(1000)
            Shots.take("overlay_folder")
        } finally { runBlocking { app.library.remove(item.id) } }
    }
    @Test fun imageSearchOpensAndAcceptsAKeyword() {
        val app = Fixtures.target.applicationContext as App
        val bytes=Fixtures.file("image_test.png").readBytes()
        val url="data:image/png;base64,"+android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
        val fixture=com.videoeditor.app.media.SearchImage(url,url,"Test image","https://example.com/photo")
        val queries=java.util.concurrent.CopyOnWriteArrayList<String>()
        rule.setContent { AppTheme { com.videoeditor.app.ui.ImageSearchDialog(app, {}, {}, searcher={q, _ ->
            queries+=q; listOf(fixture)
        }) } }
        rule.onNodeWithTag("imageSearchQuery").performTextInput("Tom Cruise movies")
        rule.onNodeWithTag("imageSearchGo").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("imageResult").fetchSemanticsNodes().size==1 }
        rule.onNodeWithTag("imageResult").performClick()
        assertEquals("Tom Cruise movies",queries.first())
        rule.onNodeWithTag("imageDetail").assertExists()
        // related images are looked up by the picture's title
        rule.waitUntil(10000) { queries.contains("Test image") }
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("Full image").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("downloadImage").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("Downloaded to Pictures/NockNock").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("saveSearchOverlay").assertIsEnabled()
        // size and the source link, like the reference; share and the menu are there
        rule.onNodeWithTag("imageInfo").assertTextContains(" - https://example.com/photo", substring=true)
        rule.onNodeWithTag("shareImage").assertIsEnabled()
        rule.onNodeWithTag("imageMenu").performClick()
        rule.onNodeWithText("Copy image link").assertExists()
        rule.onNodeWithText("Copy source link").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Source link copied").fetchSemanticsNodes().isNotEmpty() }
        Shots.take("image_detail")
        rule.onNodeWithContentDescription("Back to images").performClick()
        rule.onNodeWithTag("imageResultsGrid").assertExists()
    }

    @Test fun liveImageGridScreenshot() {
        val app=Fixtures.target.applicationContext as App
        rule.setContent { AppTheme { com.videoeditor.app.ui.ImageSearchDialog(app, {}, {}) } }
        rule.onNodeWithTag("imageSearchQuery").performTextInput("mimi chakraborty")
        rule.onNodeWithTag("imageSearchGo").performClick()
        rule.waitUntil(45000) { rule.onAllNodesWithTag("imageResult").fetchSemanticsNodes().isNotEmpty() }
        android.os.SystemClock.sleep(8000)
        Shots.take("image_search")
        rule.onAllNodesWithTag("imageResult")[0].performClick()
        rule.waitUntil(45000) { rule.onAllNodesWithContentDescription("Full image").fetchSemanticsNodes().isNotEmpty() }
        Shots.take("image_search_full")
    }
}
