package com.videoeditor.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.data.*
import com.videoeditor.app.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrendingUiTest {
    @get:Rule val rule=createComposeRule()
    @Test fun sortCopySwitchPreviewAndInsert() {
        val app=Fixtures.target.applicationContext as App
        val item=runBlocking {app.library.importItems(LibraryKind.VIDEO_FX,listOf(Fixtures.uri("portrait_test.mp4"))).single().also {app.library.rename(it.id,"Trending UI unique")}}
        var inserted:Long?=null
        var added=false
        val before=runBlocking {app.library.observe(LibraryKind.TRENDING).first().map {it.id}.toSet()}
        try {
            rule.setContent {AppTheme {
                var kind by remember {mutableStateOf(LibraryKind.VIDEO_FX)}
                LibraryDialog(app,kind,{}, {added=true}, {inserted=it.id},onKindChange={kind=it})
            }}
            rule.onNodeWithText("Search by name").performTextInput("Trending UI unique")
            rule.onNodeWithTag("librarySort").performClick()
            rule.onNodeWithText("Name: A–Z").performClick()
            assertEquals("NAME_ASC",app.getSharedPreferences("library_display",0).getString("sort_VIDEO_FX",null))
            rule.onNodeWithText("Copy to Trending").performClick()
            rule.waitUntil(10000) {rule.onAllNodesWithText("Copied to Trending").fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithTag("libraryTab_TRENDING").performClick()
            rule.waitUntil(10000) {rule.onAllNodesWithText("Trending UI unique").fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithText("Add Video").performClick();assertTrue(added)
            rule.onNodeWithContentDescription("Play preview").performClick()
            rule.onNodeWithContentDescription("Stop preview").assertExists()
            rule.onNodeWithContentDescription("Stop preview").performClick()
            rule.onNodeWithContentDescription("Rename").assertExists()
            rule.onNodeWithContentDescription("Remove").assertExists()
            Shots.take("trending_library")
            rule.onNodeWithContentDescription("Insert Trending UI unique").performClick()
            assertNotNull(inserted);assertNotEquals(item.id,inserted)
        } finally {runBlocking {
            app.library.remove(item.id)
            app.library.observe(LibraryKind.TRENDING).first().filter {it.id !in before}.forEach {app.library.remove(it.id)}
        }}
    }
}
