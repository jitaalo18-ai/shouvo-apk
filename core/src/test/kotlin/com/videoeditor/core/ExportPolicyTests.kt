package com.videoeditor.core

import org.junit.Assert.*
import org.junit.Test

class ExportPolicyTests {
    private fun clip() = Clip("v", Track.MAIN, MediaKind.VIDEO, "file:///video.mp4", sourceDurationMs = 60_000,
        sourceStartMs = 0, sourceEndMs = 60_000, timelineStartMs = 0, hasAudio = true, aspect = 16f/9f,
        scale = TimelineOps.MAIN_SCALE_UNIT, audio = AudioFx(isolate = true))
    @Test fun onlyUntouchedIsolatedPictureUsesPassthrough() {
        val c = clip()
        assertTrue(ExportPolicy.canCopyVideo(Timeline(listOf(c)), c.aspect))
        for (edited in listOf(c.copy(sourceStartMs=100), c.copy(sourceEndMs=59_000), c.copy(rotation=90f),
            c.copy(scale=2f), c.copy(posX=0.6f), c.copy(stretchY=1.1f), c.copy(audio=AudioFx.NONE))) {
            assertFalse(ExportPolicy.canCopyVideo(Timeline(listOf(edited)), c.aspect))
        }
        assertFalse(ExportPolicy.canCopyVideo(Timeline(listOf(c)), 9f/16f))
        assertFalse(ExportPolicy.canCopyVideo(Timeline(listOf(c, c.copy(id="music",track=Track.MUSIC))), c.aspect))
    }
    @Test fun audioChangesStillUseTheAudioEncoder() {
        val c = clip().copy(audio=AudioFx(isolate=true,volume=0.5f,fadeInMs=1000))
        assertTrue(ExportPolicy.canCopyVideo(Timeline(listOf(c)), c.aspect))
    }
    @Test fun multiGigabyteSpaceArithmeticDoesNotOverflowInt() {
        assertEquals(8_000_000_000L + 3600000L * 48 + 128L*1024*1024,
            ExportPolicy.requiredSpace(4_000_000_000L, 3_600_000L))
    }
}
