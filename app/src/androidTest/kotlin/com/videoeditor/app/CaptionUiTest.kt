package com.videoeditor.app

import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.captions.CaptionModel
import com.videoeditor.app.captions.CaptionModels
import com.videoeditor.app.data.LibraryKind
import com.videoeditor.app.ui.EditorController
import com.videoeditor.core.Captions
import com.videoeditor.core.MediaKind
import com.videoeditor.core.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Auto Captions and Video FX through the real UI. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CaptionUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val editor: EditorController get() = EditorController.current ?: error("editor not open")

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) {
                try {
                    condition()
                } catch (e: IllegalStateException) {
                    if (e.message?.contains("No compose hierarchies") == true) {
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                            .executeShellCommand("am start --activity-reorder-to-front -n com.videoeditor.app/.MainActivity").close()
                        Thread.sleep(1_000)
                        false
                    } else throw e
                }
            }
        } catch (e: Throwable) {
            Shots.take("FAILED_$what")
            throw AssertionError("timed out waiting for: $what", e)
        }
    }

    private fun pause(ms: Long) {
        Thread.sleep(ms)
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
        Thread.sleep(600)
    }

    private fun tool(label: String) {
        rule.onNodeWithText(label).performScrollTo().performClick()
        pause(700)
    }

    @Test fun autoCaptionsAndVideoFx() {
        val app = Fixtures.target.applicationContext as App
        val model = CaptionModel.SMALL
        assertTrue("small model pushed by CI", CaptionModels.isReady(app, model) || Pushed.copyTo(model.fileName, CaptionModels.file(app, model)))
        val video = Pushed.file("speech_en.mp4")
        assertNotNull("English speech pushed by CI", video)
        val projectId = runBlocking {
            app.projects.create(listOf(com.videoeditor.app.media.MediaProbe.probe(app, Uri.fromFile(video))!!))
        }
        waitFor("project listed") { rule.onAllNodes(hasText("Edited", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        pause(1_000)
        rule.onAllNodes(hasText("Edited", substring = true))[0].performClick()
        waitFor("editor loaded") { EditorController.current?.loaded == true && EditorController.current?.projectId == projectId }
        pause(1_500)

        // ---- Captions -> English -> Create
        tool("Captions")
        rule.onNodeWithTag("lang_ENGLISH").performClick()
        pause(500)
        Shots.take("50_caption_sheet")
        rule.onNodeWithTag("captionsCreate").performClick()
        waitFor("captions made", 240_000) { editor.busy == null && editor.timeline.track(Track.TEXT).isNotEmpty() || editor.message?.contains("fail") == true }
        pause(1_000)
        val captions = editor.timeline.track(Track.TEXT)
        Report.note("ui: ${captions.size} captions: " + captions.joinToString(" | ") { "${it.timelineStartMs}: ${it.text}" })
        assertTrue("captions on the text track", captions.size >= 3)
        assertTrue(captions.all { it.kind == MediaKind.TEXT && it.name == Captions.AUTO_NAME })

        // the first caption shows in the preview at its time
        val first = captions.first()
        rule.runOnUiThread { editor.player.seekTo(first.timelineStartMs + 200) }
        pause(1_000)
        Shots.take("51_caption_preview")
        assertTrue(
            "caption drawn in the preview",
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(first.text)).fetchSemanticsNodes().isNotEmpty(),
        )

        // ---- tap the caption on the timeline, edit its words
        rule.onNodeWithTag("chip_TEXT_0").performClick()
        pause(600)
        assertEquals(first.id, editor.timeline.selected?.id)
        tool("Edit")
        rule.onNodeWithTag("textField").performTextReplacement("Hello everybody")
        rule.onNodeWithTag("textDone").performClick()
        pause(800)
        assertEquals("Hello everybody", editor.timeline.byId(first.id)?.text)
        Shots.take("52_caption_edited")

        // ---- Style: all auto captions change together
        tool("Style")
        rule.onNodeWithTag("style_BOX").performClick()
        pause(600)
        Shots.take("53_caption_style")
        rule.onNodeWithTag("textStyleDone").performClick()
        pause(600)
        assertTrue(editor.timeline.track(Track.TEXT).all { it.style == "BOX" })
        rule.onNodeWithContentDescription("Undo").performClick()
        pause(800)
        assertTrue(editor.timeline.track(Track.TEXT).all { it.style != "BOX" })

        // ---- Video FX from the library goes on the overlay layer, the main video stays
        val mainBefore = editor.timeline.main
        val item = runBlocking {
            app.library.import(LibraryKind.VIDEO_FX, listOf(Fixtures.uri("portrait_test.mp4")))
            app.library.observe(LibraryKind.VIDEO_FX).first().first()
        }
        rule.runOnUiThread {
            editor.player.seekTo(2_000)
            editor.insertFromLibrary(LibraryKind.VIDEO_FX, item)
        }
        pause(1_200)
        Shots.take("54_video_fx_overlay")
        assertEquals("main track unchanged", mainBefore.map { it.id to it.durationMs }, editor.timeline.main.map { it.id to it.durationMs })
        val fx = editor.timeline.track(Track.OVERLAY).single()
        assertEquals(item.id, fx.libraryItemId)
        assertEquals(2_000L, fx.timelineStartMs)
        assertTrue(fx.selected)
    }
}
