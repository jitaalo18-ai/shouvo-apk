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
        rule.setContent { AppTheme { com.videoeditor.app.ui.ImageSearchDialog(app, {}, {}) } }
        rule.onNodeWithTag("imageSearchScreen").assertExists()
        rule.onNodeWithTag("imageSearchQuery").performTextInput("Tom Cruise movies")
        rule.onNodeWithTag("imageSearchGo").performClick()
        rule.onNodeWithTag("imageSearchQuery").assertTextContains("Tom Cruise movies")
        rule.onNodeWithText("Bing").performClick()
        rule.onNodeWithText("Reload").assertExists()
        rule.waitForIdle()
        android.os.SystemClock.sleep(10000)
        Shots.take("image_search")
    }

}
