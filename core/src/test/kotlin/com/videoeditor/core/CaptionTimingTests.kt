package com.videoeditor.core

import org.junit.Assert.*
import org.junit.Test

class CaptionTimingTests {
    private fun clip() = Captions.textClip("c", 1000, 3000, "hello beautiful world", style = "KARAOKE")
        .copy(captionTiming = "0,700;800,1700;1800,2600")
    @Test fun highlightsOnlyTheSpokenWordAndSupportsSeeking() {
        val c = clip()
        assertEquals(-1, CaptionTiming.active(c, 999))
        assertEquals(0, CaptionTiming.active(c, 1100))
        assertEquals(-1, CaptionTiming.active(c, 1750))
        assertEquals(2, CaptionTiming.active(c, 3000))
        assertEquals(1, CaptionTiming.active(c, 2200))
        assertEquals(-1, CaptionTiming.active(c, 4000))
        assertEquals(emptyList<MsRange>(), CaptionTiming.decode("broken"))
    }
    @Test fun timingSurvivesMoveSplitAndBothTrimEdges() {
        val c = clip()
        assertEquals(1, CaptionTiming.active(c.copy(timelineStartMs=9000), 10200))
        val split = TimelineOps.split(Timeline(listOf(c)), c.id, 2100) { "right" }
        val right = split.timeline.byId("right")!!
        assertEquals(1, CaptionTiming.active(right, 2200))
        val leftTrim = TimelineOps.trimStart(Timeline(listOf(right)), right.id, 2200).byId(right.id)!!
        assertEquals(1, CaptionTiming.active(leftTrim, 2300))
        val endTrim = TimelineOps.trimEnd(Timeline(listOf(right)), right.id, 3500).byId(right.id)!!
        assertEquals(1, CaptionTiming.active(endTrim, 2200))
    }
    @Test fun alignmentKeepsWordTimesOnOriginalSpeech() {
        val p = SpeechPiece(0, 3000, "hello world", listOf(MsRange(1000,1400), MsRange(1600,2100)))
        val aligned = Captions.alignToSpeech(listOf(p), listOf(MsRange(1000,2100)))
        val c = Captions.build(aligned, 4000, { "c" }).single()
        assertEquals(0, CaptionTiming.active(c, 1100))
        assertEquals(1, CaptionTiming.active(c, 1700))
        assertEquals(-1, CaptionTiming.active(c, 2300))
    }
    @Test fun editingTextCannotLeaveStaleWordIndices() {
        val c = clip()
        assertEquals("", Captions.setText(Timeline(listOf(c)), c.id, "new phrase").byId(c.id)!!.captionTiming)
        assertEquals(c.captionTiming, Captions.setText(Timeline(listOf(c)), c.id, "Hi beautiful world").byId(c.id)!!.captionTiming)
    }
}
