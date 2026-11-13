package com.videoeditor.core

import org.junit.Assert.*
import org.junit.Test

class ExtractAudioTests {
    private fun video() = Clip(
        id = "video", track = Track.MAIN, kind = MediaKind.VIDEO,
        sourceUri = "file:///source.mp4", mimeType = "video/mp4", name = "Clip",
        libraryItemId = 8L, sourceDurationMs = 20000, sourceStartMs = 4000,
        sourceEndMs = 13000, timelineStartMs = 3000, hasAudio = true, selected = true,
    )

    @Test fun trimmedVideoKeepsExactAudioMappingAndLibraryReference() {
        val v = video()
        val before = Timeline(listOf(v))
        val result = TimelineOps.extractAudio(before, v.id) { "audio" }
        assertTrue(result.changed)
        val audio = result.timeline.byId("audio")!!
        assertEquals(MediaKind.AUDIO, audio.kind)
        assertEquals(Track.SFX, audio.track)
        assertEquals(v.sourceUri, audio.sourceUri)
        assertEquals(v.libraryItemId, audio.libraryItemId)
        assertEquals(v.sourceStartMs, audio.sourceStartMs)
        assertEquals(v.sourceEndMs, audio.sourceEndMs)
        assertEquals(v.timelineStartMs, audio.timelineStartMs)
        for (ms in 3000L until 12000L step 97) assertEquals(v.timelineToSource(ms), audio.timelineToSource(ms))
        assertEquals(before.durationMs, result.timeline.durationMs)
        assertFalse(result.timeline.byId(v.id)!!.hasAudio)
        assertTrue(before.byId(v.id)!!.hasAudio) // immutable snapshot used by Undo
        assertEquals(1, result.timeline.clips.count { it.selected })
        assertTrue(audio.selected)
    }

    @Test fun cannotExtractTwiceOrFromSilentOrNonVideoClips() {
        val v = video()
        val extracted = TimelineOps.extractAudio(Timeline(listOf(v)), v.id) { "a" }.timeline
        assertFalse(TimelineOps.extractAudio(extracted, v.id) { "b" }.changed)
        for (clip in listOf(v.copy(hasAudio = false), v.copy(kind = MediaKind.IMAGE), v.copy(kind = MediaKind.AUDIO))) {
            val t = Timeline(listOf(clip))
            assertEquals(t, TimelineOps.extractAudio(t, clip.id) { "a" }.timeline)
        }
    }

    @Test fun overlayAudioDetachesWithoutMovingVideo() {
        val v = video().copy(track = Track.OVERLAY, rotation = 90f, stretchX = 2f)
        val out = TimelineOps.extractAudio(Timeline(listOf(v)), v.id) { "a" }.timeline
        assertEquals(v.copy(hasAudio = false, selected = false), out.byId(v.id))
        assertEquals(0f, out.byId("a")!!.rotation, 0f)
        assertEquals(1f, out.byId("a")!!.stretchX, 0f)
    }

    @Test fun movingSplittingOrDeletingExtractedAudioLeavesVideoUnchanged() {
        val v = video()
        val out = TimelineOps.extractAudio(Timeline(listOf(v)), v.id) { "a" }.timeline
        val muted = out.byId(v.id)
        val moved = TimelineOps.move(out, "a", 5000)
        assertEquals(muted, moved.byId(v.id))
        val split = TimelineOps.split(moved, "a", 8000) { "b" }
        assertTrue(split.changed)
        assertEquals(muted, split.timeline.byId(v.id))
        assertEquals(muted, TimelineOps.remove(split.timeline, "a").byId(v.id))
    }
}
