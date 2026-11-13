package com.videoeditor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private fun video(id: String, dur: Long, start: Long = 0, uri: String = "file:///$id.mp4") = Clip(
    id = id, track = Track.MAIN, kind = MediaKind.VIDEO, sourceUri = uri, sourceDurationMs = dur,
    sourceStartMs = 0, sourceEndMs = dur, timelineStartMs = start, hasAudio = true,
)

private class Ids {
    private var n = 0
    val next: () -> String = { "n${n++}" }
}

private fun assertContiguous(t: Timeline) {
    var at = 0L
    for (c in t.main) {
        assertEquals("clip ${c.id} start", at, c.timelineStartMs)
        assertTrue(c.durationMs > 0)
        at = c.timelineEndMs
    }
    assertEquals(at, t.durationMs)
}

class SplitTests {
    @Test fun splitMakesTwoIndependentClips() {
        val t = TimelineOps.appendMain(Timeline(), listOf(video("a", 10_000)))
        val r = TimelineOps.split(t, "a", 4_000, Ids().next)
        assertTrue(r.changed)
        val m = r.timeline.main
        assertEquals(2, m.size)
        assertEquals(MsRange(0, 4_000), MsRange(m[0].sourceStartMs, m[0].sourceEndMs))
        assertEquals(MsRange(4_000, 10_000), MsRange(m[1].sourceStartMs, m[1].sourceEndMs))
        assertEquals(4_000, m[1].timelineStartMs)
        assertEquals(m[0].sourceUri, m[1].sourceUri)
        assertTrue(m[0].id != m[1].id)
        assertContiguous(r.timeline)
    }

    @Test fun repeatedSplitsKeepDurationAndSourceMapping() {
        var t = TimelineOps.appendMain(Timeline(), listOf(video("a", 60_000), video("b", 30_000)))
        val ids = Ids()
        val rnd = Random(7)
        repeat(200) {
            val at = (rnd.nextDouble() * t.durationMs).toLong()
            val pos = TimelineOps.locate(t, at)!!
            t = TimelineOps.split(t, pos.clip.id, at, ids.next).timeline
            assertContiguous(t)
            assertEquals(90_000, t.durationMs)
        }
        assertTrue(t.main.size > 50)
        // Every timeline instant must still map to the same source instant as before any split.
        for (ms in 0 until 90_000 step 37) {
            val p = TimelineOps.locate(t, ms.toLong())!!
            if (ms < 60_000) {
                assertEquals("file:///a.mp4", p.clip.sourceUri); assertEquals(ms.toLong(), p.sourceMs)
            } else {
                assertEquals("file:///b.mp4", p.clip.sourceUri); assertEquals(ms - 60_000L, p.sourceMs)
            }
        }
    }

    @Test fun splitAtEdgeIsRejectedAndTimelineUnchanged() {
        val t = TimelineOps.appendMain(Timeline(), listOf(video("a", 10_000)))
        for (at in listOf(0L, 50L, 9_960L, 10_000L, 20_000L)) {
            val r = TimelineOps.split(t, "a", at, Ids().next)
            assertFalse(r.changed); assertEquals(t, r.timeline); assertNotNull(r.reason)
        }
    }

    @Test fun splitOfAlreadyTrimmedSecondClipMapsToSource() {
        var t = TimelineOps.appendMain(Timeline(), listOf(video("a", 5_000), video("b", 20_000)))
        t = TimelineOps.split(t, "b", 9_000, { "b2" }).timeline      // b: 0..4000 | b2: 4000..20000
        t = TimelineOps.split(t, "b2", 12_500, { "b3" }).timeline    // b2: 4000..7500 | b3: 7500..20000
        val b3 = t.byId("b3")!!
        assertEquals(7_500, b3.sourceStartMs); assertEquals(12_500, b3.timelineStartMs)
        assertEquals(8_000, TimelineOps.locate(t, 13_000)!!.sourceMs)
    }
}

class TimelineEditTests {
    @Test fun insertInsideClipSplitsIt() {
        val t = TimelineOps.appendMain(Timeline(), listOf(video("a", 10_000)))
        val out = TimelineOps.insertMainAt(t, 3_000, video("fx", 2_000), { "a2" })
        assertEquals(listOf("a", "fx", "a2"), out.main.map { it.id })
        assertEquals(12_000, out.durationMs)
        assertEquals(3_000, out.byId("a2")!!.sourceStartMs)
        assertContiguous(out)
    }

    @Test fun insertAtStartAndEnd() {
        val t = TimelineOps.appendMain(Timeline(), listOf(video("a", 10_000)))
        assertEquals(listOf("fx", "a"), TimelineOps.insertMainAt(t, 0, video("fx", 2_000), { "x" }).main.map { it.id })
        assertEquals(listOf("a", "fx"), TimelineOps.insertMainAt(t, 10_000, video("fx", 2_000), { "x" }).main.map { it.id })
    }

    @Test fun overlaysAndAudioDoNotChangeMainTrack() {
        var t = TimelineOps.appendMain(Timeline(), listOf(video("a", 10_000)))
        val ov = video("o1", 4_000).copy(track = Track.OVERLAY, timelineStartMs = 2_000)
        val ov2 = video("o2", 4_000).copy(track = Track.OVERLAY, timelineStartMs = 3_000)
        val sfx = video("s1", 1_000).copy(track = Track.SFX, kind = MediaKind.AUDIO, timelineStartMs = 5_000)
        t = TimelineOps.addClip(TimelineOps.addClip(TimelineOps.addClip(t, ov), ov2), sfx)
        assertEquals(10_000, t.durationMs)
        assertEquals(listOf(0, 1), t.track(Track.OVERLAY).map { it.layer })
        assertEquals(mapOf("o1" to 0, "o2" to 1), TimelineOps.lanes(t.track(Track.OVERLAY)))
        t = TimelineOps.move(t, "o1", 7_000)
        assertEquals(7_000, t.byId("o1")!!.timelineStartMs)
        t = TimelineOps.trimEnd(t, "o1", 8_500)
        assertEquals(1_500, t.byId("o1")!!.durationMs)
        t = TimelineOps.trimStart(t, "o1", 7_500)
        assertEquals(7_500, t.byId("o1")!!.timelineStartMs); assertEquals(500, t.byId("o1")!!.sourceStartMs)
        assertEquals(8_500, t.byId("o1")!!.timelineEndMs)
        t = TimelineOps.trimEnd(t, "o1", 99_000)     // cannot extend past the source
        assertEquals(4_000, t.byId("o1")!!.sourceEndMs)
        t = TimelineOps.remove(t, "o1")
        assertEquals(listOf("a"), t.main.map { it.id }); assertEquals(1, t.track(Track.OVERLAY).size)
        t = TimelineOps.transform(t, "o2", 2f, -1f, 99f)
        val o2 = t.byId("o2")!!
        assertEquals(1f, o2.posX); assertEquals(0f, o2.posY); assertEquals(TimelineOps.MAX_SCALE, o2.scale)
    }

    @Test fun imageOverlayCanBeExtended() {
        var t = TimelineOps.appendMain(Timeline(), listOf(video("a", 10_000)))
        val img = Clip("i", Track.OVERLAY, MediaKind.IMAGE, "content://img", sourceDurationMs = 0,
            sourceStartMs = 0, sourceEndMs = 3_000, timelineStartMs = 1_000, hasAudio = false)
        t = TimelineOps.addClip(t, img)
        t = TimelineOps.trimEnd(t, "i", 9_000)
        assertEquals(8_000, t.byId("i")!!.durationMs)
        t = TimelineOps.trimStart(t, "i", 4_000)
        assertEquals(4_000, t.byId("i")!!.timelineStartMs); assertEquals(9_000, t.byId("i")!!.timelineEndMs)
    }

    @Test fun deletingMainClipClosesTheGap() {
        var t = TimelineOps.appendMain(Timeline(), listOf(video("a", 4_000), video("b", 3_000), video("c", 2_000)))
        t = TimelineOps.remove(t, "b")
        assertEquals(listOf("a", "c"), t.main.map { it.id })
        assertEquals(4_000, t.byId("c")!!.timelineStartMs); assertEquals(6_000, t.durationMs)
        assertContiguous(t)
    }

    @Test fun mainZoomDefaultsToOneAndSurvivesSplit() {
        var t = TimelineOps.appendMain(Timeline(), listOf(video("a", 10_000)))
        assertEquals(1f, TimelineOps.mainZoom(t.byId("a")!!))            // old projects: fitted
        t = TimelineOps.transformMain(t, "a", 0.6f, 0.4f, 2f)
        assertEquals(2f, TimelineOps.mainZoom(t.byId("a")!!), 1e-5f)
        t = TimelineOps.split(t, "a", 5_000, { "a2" }).timeline
        assertEquals(2f, TimelineOps.mainZoom(t.byId("a2")!!), 1e-5f); assertEquals(0.6f, t.byId("a2")!!.posX)
        t = TimelineOps.transformMain(t, "a", 0.5f, 0.5f, 100f)
        assertEquals(TimelineOps.MAX_MAIN_ZOOM, TimelineOps.mainZoom(t.byId("a")!!), 1e-4f)
        assertEquals(10_000, t.durationMs)
    }

    @Test fun duplicateOverlayGoesOnTopAndIsSelected() {
        var t = TimelineOps.appendMain(Timeline(), listOf(video("a", 10_000)))
        t = TimelineOps.addClip(t, video("o1", 4_000).copy(track = Track.OVERLAY, timelineStartMs = 2_000))
        t = TimelineOps.duplicate(t, "o1", { "o2" })
        val o2 = t.byId("o2")!!
        assertEquals(1, o2.layer); assertTrue(o2.selected); assertEquals(2_000, o2.timelineStartMs)
        assertEquals(t, TimelineOps.duplicate(t, "a", { "x" }))           // main clips are not duplicated here
    }

    @Test fun rotationIsStoredNormalizedAndSurvivesSplitAndAutoCut() {
        var t = TimelineOps.appendMain(Timeline(), listOf(video("a", 10_000)))
        assertEquals(0f, t.byId("a")!!.rotation)
        t = TimelineOps.transformMain(t, "a", 0.5f, 0.5f, 1.45f, 22f)
        assertEquals(22f, t.byId("a")!!.rotation)
        t = TimelineOps.transformMain(t, "a", 0.5f, 0.5f, 1.45f)                 // rotation untouched when not given
        assertEquals(22f, t.byId("a")!!.rotation)
        t = TimelineOps.split(t, "a", 5_000, { "a2" }).timeline
        assertEquals(22f, t.byId("a2")!!.rotation)
        t = TimelineOps.applyKeepRanges(t, "a", listOf(MsRange(0, 1_000), MsRange(2_000, 5_000)), Ids().next).timeline
        assertTrue(t.main.all { it.rotation == 22f })
        assertEquals(-170f, TimelineOps.normalizeAngle(190f), 1e-4f)
        assertEquals(180f, TimelineOps.normalizeAngle(-180f), 1e-4f)
        assertEquals(10f, TimelineOps.normalizeAngle(730f), 1e-3f)
        t = TimelineOps.addClip(t, video("o", 2_000).copy(track = Track.OVERLAY))
        t = TimelineOps.transform(t, "o", 0.5f, 0.5f, 0.4f, 400f)
        assertEquals(40f, t.byId("o")!!.rotation, 1e-3f)
    }

    @Test fun trimMainClipKeepsTrackGapless() {
        var t = TimelineOps.appendMain(Timeline(), listOf(video("a", 10_000), video("b", 5_000)))
        t = TimelineOps.trimMain(t, "a", newSourceStartMs = 2_000)
        assertEquals(2_000, t.byId("a")!!.sourceStartMs); assertEquals(0, t.byId("a")!!.timelineStartMs)
        assertEquals(8_000, t.byId("b")!!.timelineStartMs); assertContiguous(t)
        t = TimelineOps.trimMain(t, "a", newSourceEndMs = 6_000)
        assertEquals(4_000, t.byId("a")!!.durationMs); assertEquals(9_000, t.durationMs)
        t = TimelineOps.trimMain(t, "a", newSourceStartMs = -500, newSourceEndMs = 99_000)   // drag back out: all restored
        assertEquals(10_000, t.byId("a")!!.durationMs); assertEquals(0, t.byId("a")!!.sourceStartMs)
        t = TimelineOps.trimMain(t, "a", newSourceStartMs = 9_990)                            // never shorter than the minimum
        assertEquals(TimelineOps.MIN_SPLIT_MS, t.byId("a")!!.durationMs)
        assertContiguous(t)
    }

    @Test fun trimImageOnMainTrackChangesDuration() {
        val img = Clip("i", Track.MAIN, MediaKind.IMAGE, "content://img", sourceDurationMs = 0,
            sourceStartMs = 0, sourceEndMs = 3_000, timelineStartMs = 0, hasAudio = false)
        var t = TimelineOps.appendMain(Timeline(), listOf(img, video("b", 5_000)))
        t = TimelineOps.trimMain(t, "i", newSourceEndMs = 7_000)
        assertEquals(7_000, t.byId("i")!!.durationMs); assertEquals(7_000, t.byId("b")!!.timelineStartMs)
        t = TimelineOps.trimMain(t, "i", newSourceStartMs = 2_000)
        assertEquals(5_000, t.byId("i")!!.durationMs); assertEquals(0, t.byId("i")!!.sourceStartMs)
    }

    @Test fun moveMainClipEarlierAndLater() {
        var t = TimelineOps.appendMain(Timeline(), listOf(video("a", 4_000), video("b", 3_000), video("c", 2_000)))
        t = TimelineOps.moveMain(t, "c", -1)
        assertEquals(listOf("a", "c", "b"), t.main.map { it.id }); assertContiguous(t)
        t = TimelineOps.moveMain(t, "a", 1)
        assertEquals(listOf("c", "a", "b"), t.main.map { it.id }); assertEquals(2_000, t.byId("a")!!.timelineStartMs)
        assertEquals(t, TimelineOps.moveMain(t, "c", -1))                                      // already first
        assertEquals(t, TimelineOps.moveMain(t, "b", 1))                                       // already last
        assertEquals(9_000, t.durationMs)
    }

    @Test fun selectIsExclusive() {
        var t = TimelineOps.appendMain(Timeline(), listOf(video("a", 1_000), video("b", 1_000)))
        t = TimelineOps.select(t, "a"); t = TimelineOps.select(t, "b")
        assertEquals("b", t.selected!!.id); assertEquals(1, t.clips.count { it.selected })
        assertEquals(null, TimelineOps.select(t, null).selected)
    }
}

/** Synthetic "voice": harmonics of a 130 Hz pitch with syllable-rate amplitude modulation. */
private fun synth(rate: Int, totalMs: Int, speech: List<MsRange>, noiseAmp: Double, voiceAmp: Double = 0.25, seed: Long = 1): ShortArray {
    val n = rate * totalMs / 1000
    val rnd = Random(seed)
    val out = ShortArray(n)
    for (i in 0 until n) {
        val tMs = i * 1000L / rate
        var v = rnd.nextGaussian() * noiseAmp
        if (speech.any { tMs >= it.startMs && tMs < it.endMs }) {
            val t = i.toDouble() / rate
            val env = 0.55 + 0.45 * sin(2 * PI * 4.0 * t)
            var s = 0.0
            for (h in 1..20) s += sin(2 * PI * 130.0 * h * t) / h
            v += voiceAmp * env * s / 2.5
        }
        out[i] = (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
    }
    return out
}

private fun detect(pcm: ShortArray, rate: Int = 16000): VadResult {
    val d = SpeechDetector(rate)
    var i = 0
    val chunk = ShortArray(1000)                     // deliberately not frame-aligned
    while (i < pcm.size) {
        val n = minOf(chunk.size, pcm.size - i)
        System.arraycopy(pcm, i, chunk, 0, n); d.accept(chunk, n); i += n
    }
    return d.finish()
}

private const val HISS_AMP = 0.03

class AutoCutTests {
    private val truth = listOf(MsRange(1_000, 3_000), MsRange(5_500, 8_000), MsRange(8_300, 9_500), MsRange(13_000, 15_000))

    @Test fun detectsSpeechOverBackgroundNoise() {
        val r = detect(synth(16000, 16_000, truth, noiseAmp = 0.01))   // noise about -40 dBFS, not silence
        assertEquals(VadStatus.OK, r.status)
        // 8000-8300 is a short pause: it may or may not be bridged, both are fine. Check coverage.
        for (t in truth) {
            val covered = r.speech.any { it.startMs <= t.startMs + 120 && it.endMs >= t.endMs - 120 } ||
                r.speech.filter { it.endMs > t.startMs && it.startMs < t.endMs }.sumOf { minOf(it.endMs, t.endMs) - maxOf(it.startMs, t.startMs) } >= t.lengthMs - 200
            assertTrue("speech $t not covered by ${r.speech}", covered)
        }
        val falseSpeech = r.speech.sumOf { it.lengthMs } - truth.sumOf { it.lengthMs }
        assertTrue("too much noise marked as speech: $falseSpeech ms in ${r.speech}", falseSpeech < 900)
    }

    @Test fun planRemovesSilenceKeepsMarginsAndShortPauses() {
        val r = detect(synth(16000, 16_000, truth, noiseAmp = 0.01))
        val plan = AutoCutPlanner.plan(r.speech, baseSourceMs = 0, clipStartMs = 0, clipEndMs = 16_000)
        assertEquals("keep=${plan.keep}", 3, plan.keep.size)                // 5.5-9.5 s stays one piece (300 ms pause kept)
        for (t in listOf(MsRange(1_000, 3_000), MsRange(5_500, 9_500), MsRange(13_000, 15_000))) {
            val k = plan.keep.first { it.endMs > t.startMs && it.startMs < t.endMs }
            assertTrue("word start clipped: $k vs $t", k.startMs <= t.startMs - 30)
            assertTrue("word end clipped: $k vs $t", k.endMs >= t.endMs + 30)
            assertTrue("margin too large: $k vs $t", k.startMs >= t.startMs - 450 && k.endMs <= t.endMs + 500)
        }
        assertTrue("removed=${plan.removedMs}", plan.removedMs in 5_500..8_500)
    }

    @Test fun planAppliedToTimelineKeepsSyncMapping() {
        val r = detect(synth(16000, 16_000, truth, noiseAmp = 0.01))
        var t = TimelineOps.appendMain(Timeline(), listOf(video("a", 16_000), video("b", 5_000)))
        val plan = AutoCutPlanner.plan(r.speech, 0, 0, 16_000)
        val res = TimelineOps.applyKeepRanges(t, "a", plan.keep, Ids().next)
        assertTrue(res.changed); t = res.timeline
        assertContiguous(t)
        assertEquals(16_000 - plan.removedMs + 5_000, t.durationMs)
        val pieces = t.main.filter { it.sourceUri == "file:///a.mp4" }
        assertEquals(plan.keep, pieces.map { MsRange(it.sourceStartMs, it.sourceEndMs) })
        assertEquals(pieces.map { it.sourceStartMs }.sorted(), pieces.map { it.sourceStartMs })   // chronological
        assertEquals("b", t.main.last().id); assertEquals(0, t.main.last().sourceStartMs)
        // original clip description is untouched apart from the range: same file, same source duration
        assertTrue(pieces.all { it.sourceDurationMs == 16_000L })
    }

    @Test fun worksOnTrimmedClipWithOffsetBase() {
        // clip uses source 20 s..36 s; decoder's first sample was at 19.98 s
        val r = detect(synth(16000, 16_020, truth.map { MsRange(it.startMs + 20, it.endMs + 20) }, 0.01))
        val plan = AutoCutPlanner.plan(r.speech, baseSourceMs = 19_980, clipStartMs = 20_000, clipEndMs = 36_000)
        assertTrue(plan.keep.all { it.startMs >= 20_000 && it.endMs <= 36_000 })
        assertTrue(plan.keep.first().startMs in 20_550..20_970)
    }

    @Test fun pureSilenceIsNotCut() {
        assertEquals(VadStatus.NO_SPEECH, detect(ShortArray(16000 * 5)).status)
    }

    @Test fun steadyNoiseOnlyIsReportedUnreliableOrNoSpeech() {
        val r = detect(synth(16000, 8_000, emptyList(), noiseAmp = 0.05))
        assertTrue(r.status == VadStatus.UNRELIABLE || r.status == VadStatus.NO_SPEECH)
        assertTrue(r.speech.isEmpty())
    }

    @Test fun noAudioAtAll() {
        assertEquals(VadStatus.NO_AUDIO, SpeechDetector(16000).finish().status)
    }

    @Test fun continuousSpeechIsKeptWhole() {
        val all = listOf(MsRange(0, 30_000))
        val r = detect(synth(16000, 30_000, all, noiseAmp = 0.005))
        if (r.status == VadStatus.OK) {
            val plan = AutoCutPlanner.plan(r.speech, 0, 0, 30_000)
            assertTrue("removed ${plan.removedMs} of continuous speech", plan.removedMs < 300)
        } else {
            assertEquals(VadStatus.UNRELIABLE, r.status)    // also safe: clip is left untouched
        }
    }

    @Test fun softWordEndingOutsideVoiceBandIsNotCut() {
        // voice 1.0-3.0 s, then an "s" sound (mostly above 4 kHz) 3.0-3.25 s, then a long pause
        val rate = 16000
        val pcm = synth(rate, 8_000, listOf(MsRange(1_000, 3_000), MsRange(5_500, 7_000)), noiseAmp = 0.002)
        // a soft "s": sound well above the voice band (6 kHz), quiet but clearly audible
        for (i in rate * 3000 / 1000 until rate * 3250 / 1000) {
            val hiss = HISS_AMP * sin(2 * PI * 6000.0 * i / rate)
            pcm[i] = (pcm[i] + hiss * 32767).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        val r = detect(pcm, rate)
        assertEquals(VadStatus.OK, r.status)
        for (strength in AutoCutStrength.values()) {
            val plan = AutoCutPlanner.plan(r.speech, 0, 0, 8_000, strength.config)
            val first = plan.keep.first { it.startMs < 3_000 }
            assertTrue("$strength cut the 's' sound: $first", first.endMs >= 3_250)
            assertTrue("$strength removed nothing: ${plan.keep}", plan.removedMs > 1_500)
        }
    }

    @Test fun strengthsAreOrdered() {
        val r = detect(synth(16000, 16_000, truth, noiseAmp = 0.01))
        val removed = AutoCutStrength.values().map { AutoCutPlanner.plan(r.speech, 0, 0, 16_000, it.config).removedMs }
        assertTrue("removed per level $removed", removed[0] <= removed[1] && removed[1] <= removed[2])
    }

    @Test fun emptyKeepIsRejected() {
        val t = TimelineOps.appendMain(Timeline(), listOf(video("a", 5_000)))
        val r = TimelineOps.applyKeepRanges(t, "a", emptyList(), Ids().next)
        assertFalse(r.changed); assertEquals(t, r.timeline)
        assertFalse(TimelineOps.applyKeepRanges(t, "a", listOf(MsRange(0, 5_000)), Ids().next).changed)
        assertFalse(TimelineOps.applyKeepRanges(t, "a", listOf(MsRange(0, 9_000)), Ids().next).changed)
    }

    @Test fun downsamplerProduces16kMono() {
        val ds = MonoDownsampler(48000, 2)
        val frames = 48000
        val input = FloatArray(frames * 2) { i -> (0.5 * sin(2 * PI * 440.0 * (i / 2) / 48000.0)).toFloat() }
        val out = ShortArray(ds.maxOut(frames))
        val n = ds.process(input, frames, out)
        assertEquals(16000, ds.effectiveRate)
        assertTrue(abs(n - 16000) <= 1)
        val peak = out.take(n).maxOf { abs(it.toInt()) }
        assertTrue("peak=$peak", peak in 14_000..17_000)
        assertEquals(8000, MonoDownsampler(8000, 1).effectiveRate)
    }

    @Test fun louderNoiseStillWorks() {
        val r = detect(synth(16000, 16_000, truth, noiseAmp = 0.03, voiceAmp = 0.35, seed = 5))
        assertEquals(VadStatus.OK, r.status)
        val plan = AutoCutPlanner.plan(r.speech, 0, 0, 16_000)
        assertTrue("removed=${plan.removedMs} keep=${plan.keep}", plan.removedMs in 4_500..8_500)
        for (t in listOf(MsRange(1_000, 3_000), MsRange(5_500, 8_000), MsRange(13_000, 15_000))) {
            assertTrue(plan.keep.any { it.startMs <= t.startMs && it.endMs >= t.endMs })
        }
    }
}

class VocalRemoverTests {
    private val rate = 44100

    private fun voice(n: Int): FloatArray = FloatArray(n) { i ->
        val t = i.toDouble() / rate
        var s = 0.0
        for (h in 1..15) s += sin(2 * PI * 220.0 * h * t) / h
        (0.2 * (0.6 + 0.4 * sin(2 * PI * 3.0 * t)) * s).toFloat()
    }

    /** Different instruments left and right, as in a normal stereo mix. */
    private fun music(n: Int): Pair<FloatArray, FloatArray> {
        val l = FloatArray(n) { i -> val t = i.toDouble() / rate; (0.2 * sin(2 * PI * 330.0 * t) + 0.12 * sin(2 * PI * 1245.0 * t)).toFloat() }
        val r = FloatArray(n) { i -> val t = i.toDouble() / rate; (0.2 * sin(2 * PI * 495.0 * t) + 0.12 * sin(2 * PI * 2093.0 * t)).toFloat() }
        return l to r
    }

    private class Out(n: Int) { val l = FloatArray(n); val r = FloatArray(n); var size = 0 }

    private fun run(l: FloatArray, r: FloatArray): Pair<Out, VocalRemovalStatus> {
        val vr = VocalRemover(rate)
        val out = Out(l.size + 8192)
        val sink = { a: FloatArray, b: FloatArray, c: Int ->
            System.arraycopy(a, 0, out.l, out.size, c); System.arraycopy(b, 0, out.r, out.size, c); out.size += c
        }
        var i = 0
        while (i < l.size) {                      // odd chunk size on purpose
            val c = minOf(3001, l.size - i)
            vr.process(l.copyOfRange(i, i + c), r.copyOfRange(i, i + c), c, sink); i += c
        }
        return out to vr.finish(sink)
    }

    private fun energy(a: FloatArray, from: Int, to: Int): Double { var s = 0.0; for (i in from until to) s += a[i] * a[i]; return s }
    private fun db(x: Double) = 10 * kotlin.math.log10(x + 1e-30)

    @Test fun centredVoiceIsRemovedAndSideMusicKept() {
        val n = rate * 4
        val v = voice(n); val (ml, mr) = music(n)
        val (out, status) = run(FloatArray(n) { v[it] + ml[it] }, FloatArray(n) { v[it] + mr[it] })
        assertEquals(VocalRemovalStatus.OK, status)
        assertEquals(n, out.size)                                   // same length, aligned
        val a = rate / 2; val b = n - rate / 2
        var err = 0.0
        for (i in a until b) { val dl = out.l[i] - ml[i]; val dr = out.r[i] - mr[i]; err += dl * dl + dr * dr }
        val musicE = energy(ml, a, b) + energy(mr, a, b)
        // what is left is the music: residual (leftover voice + lost music) at least 12 dB below it
        assertTrue("residual ${db(err / musicE)} dB", db(err / musicE) < -12.0)
    }

    @Test fun voiceAloneIsStronglyReduced() {
        val n = rate * 3
        val v = voice(n)
        // tiny uncorrelated hiss so the source is not perfectly mono
        val rnd = Random(3)
        val l = FloatArray(n) { v[it] + (rnd.nextGaussian() * 0.02).toFloat() }
        val r = FloatArray(n) { v[it] + (rnd.nextGaussian() * 0.02).toFloat() }
        val (out, _) = run(l, r)
        val a = rate / 2; val b = n - rate / 2
        val reduction = db((energy(out.l, a, b) + energy(out.r, a, b)) / (2 * energy(v, a, b)))
        assertTrue("voice only reduced by $reduction dB", reduction < -15.0)
    }

    @Test fun pureStereoMusicPassesAlmostUnchanged() {
        val n = rate * 3
        val (ml, mr) = music(n)
        val (out, status) = run(ml, mr)
        assertEquals(VocalRemovalStatus.OK, status)
        val a = rate / 2; val b = n - rate / 2
        val change = db((energy(out.l, a, b) + energy(out.r, a, b)) / (energy(ml, a, b) + energy(mr, a, b)))
        assertTrue("music level changed by $change dB", change > -1.5 && change < 0.5)
    }

    @Test fun centredBassIsKept() {
        val n = rate * 3
        val bass = FloatArray(n) { (0.3 * sin(2 * PI * 55.0 * it / rate)).toFloat() }
        val (ml, mr) = music(n)
        val (out, _) = run(FloatArray(n) { bass[it] + ml[it] }, FloatArray(n) { bass[it] + mr[it] })
        val a = rate / 2; val b = n - rate / 2
        var dot = 0.0
        for (i in a until b) dot += out.l[i] * bass[i]
        val kept = dot / energy(bass, a, b)                         // projection onto the bass tone
        assertTrue("bass kept factor $kept", kept > 0.85)
    }

    @Test fun monoSourceIsReportedNotSaved() {
        val n = rate * 2
        val v = voice(n)
        assertEquals(VocalRemovalStatus.MONO_SOURCE, run(v, v.copyOf()).second)
        assertEquals(VocalRemovalStatus.EMPTY, VocalRemover(rate).finish { _, _, _ -> })
        assertEquals(VocalRemovalStatus.EMPTY, run(FloatArray(rate), FloatArray(rate)).second)
    }

    @Test fun fftRoundTrip() {
        val f = Fft(1024); val rnd = Random(1)
        val re = FloatArray(1024) { rnd.nextFloat() - 0.5f }; val im = FloatArray(1024)
        val orig = re.copyOf()
        f.forward(re, im); f.inverse(re, im)
        for (i in orig.indices) assertTrue(abs(re[i] - orig[i]) < 1e-4f && abs(im[i]) < 1e-4f)
    }
}

class FramingTests {
    private val wide = 16f / 9f      // landscape clip
    private val tall = 9f / 16f      // 9:16 frame

    @Test fun landscapeClipInTallFrameFitsWidth() {
        val (w, h) = Framing.fitFractions(wide, tall)
        assertEquals(1f, w, 1e-5f); assertEquals(tall / wide, h, 1e-5f)
    }

    @Test fun snapsToCentreAndStraightAngle() {
        val r = Framing.snap(FrameState(0.507f, 0.49f, 1.4f, 2.5f), wide, tall)
        assertEquals(0.5f, r.state.x); assertEquals(0.5f, r.state.y); assertEquals(0f, r.state.rotation)
        assertTrue(r.centerX && r.centerY && r.angle)
        val free = Framing.snap(FrameState(0.3f, 0.2f, 1.4f, 22f), wide, tall)
        assertEquals(FrameState(0.3f, 0.2f, 1.4f, 22f), free.state); assertEquals(0, free.key)
    }

    @Test fun snapsBottomEdgeToFrameEdge() {
        // fitted landscape clip in a 9:16 frame is 0.316 of the frame height; zoom 2 -> 0.633
        val h = tall / wide * 2f
        val r = Framing.snap(FrameState(0.4f, 1f - h / 2f + 0.01f, 2f, 0f), wide, tall)
        assertTrue(r.bottom); assertFalse(r.top); assertFalse(r.centerY)
        assertEquals(1f - h / 2f, r.state.y, 1e-5f)
        val top = Framing.snap(FrameState(0.4f, h / 2f - 0.01f, 2f, 0f), wide, tall)
        assertTrue(top.top); assertEquals(h / 2f, top.state.y, 1e-5f)
    }

    @Test fun edgesDoNotSnapWhileTilted() {
        val h = tall / wide * 2f
        val r = Framing.snap(FrameState(0.4f, 1f - h / 2f + 0.01f, 2f, 22f), wide, tall)
        assertFalse(r.bottom); assertFalse(r.angle)
    }

    @Test fun snapsToFillZoomAndReportsAllEdges() {
        val fill = wide / tall                       // zoom needed for the clip to cover the frame height
        val r = Framing.snap(FrameState(0.5f, 0.5f, fill * 1.02f, 0f), wide, tall)
        assertTrue(r.zoom); assertEquals(fill, r.state.zoom, 1e-4f)
        assertTrue(r.top && r.bottom && r.centerX && r.centerY)
        val fit = Framing.snap(FrameState(0.5f, 0.5f, 0.99f, 0f), wide, tall)
        assertEquals(1f, fit.state.zoom, 1e-5f); assertTrue(fit.left && fit.right)
    }

    @Test fun quarterTurnSwapsWidthAndHeight() {
        // rotated by 90 degrees the landscape clip becomes portrait and fits a 9:16 frame at zoom 16/9
        val r = Framing.snap(FrameState(0.5f, 0.5f, wide * 0.99f, 88f), wide, tall)
        assertEquals(90f, r.state.rotation); assertEquals(wide, r.state.zoom, 1e-3f)
        assertTrue(r.left && r.right && r.top && r.bottom)
    }

    @Test fun newSnapChangesTheKey() {
        val a = Framing.snap(FrameState(0.3f, 0.2f, 1.4f, 22f), wide, tall).key
        val b = Framing.snap(FrameState(0.3f, 0.2f, 1.4f, 1f), wide, tall).key
        assertTrue(b and a.inv() != 0)
    }

    @Test fun stretchIsClampedAndKept() {
        var t = TimelineOps.appendMain(Timeline(), listOf(video("a", 10_000)))
        t = TimelineOps.stretch(t, "a", 1.5f, 99f)
        assertEquals(1.5f, t.byId("a")!!.stretchX); assertEquals(TimelineOps.MAX_STRETCH, t.byId("a")!!.stretchY)
        t = TimelineOps.split(t, "a", 4_000, { "a2" }).timeline
        assertEquals(1.5f, t.byId("a2")!!.stretchX)
    }
}

class SnapTests {
    @Test
    fun mainEdgeIgnoresTheClipsThatRippleWithIt() {
        val a = Clip(id = "a", track = Track.MAIN, kind = MediaKind.VIDEO, sourceUri = "u", sourceDurationMs = 10000, sourceStartMs = 0, sourceEndMs = 3000, timelineStartMs = 0, hasAudio = true)
        val b = a.copy(id = "b", sourceStartMs = 3000, sourceEndMs = 5000)
        val t = TimelineOps.normalize(Timeline(listOf(a, b)))
        val targets = Snap.mainEdgeTargets(t, "a", playheadMs = 0)
        assertFalse("the next clip's start is not a target", 3000L in targets)
        assertFalse(5000L in targets)
        // pulling the edge 200 ms to the left is not pulled back to where it was
        assertEquals(2800L, Snap.edge(2800, targets, 300).value)
    }

    private val t = TimelineOps.appendMain(Timeline(), listOf(video("a", 4_000), video("b", 6_000)))
        .let { TimelineOps.addClip(it, video("s", 1_000).copy(track = Track.SFX, kind = MediaKind.AUDIO, timelineStartMs = 7_000)) }

    @Test fun targetsIncludePlayheadEdgesAndStart() {
        val targets = Snap.targets(t, excludeId = "s", playheadMs = 2_500)
        assertEquals(listOf(0L, 2_500L, 4_000L, 10_000L), targets)
        assertTrue(7_000L in Snap.targets(t, excludeId = null, playheadMs = 0))
    }

    @Test fun moveSnapsStartOrEndWhicheverIsCloser() {
        val targets = Snap.targets(t, "s", 2_500)
        assertEquals(Snapped(4_000, 4_000), Snap.move(4_120, 1_000, targets, 200))        // start onto clip edge
        assertEquals(Snapped(1_500, 2_500), Snap.move(1_430, 1_000, targets, 200))        // end onto playhead
        assertEquals(Snapped(5_000, null), Snap.move(5_000, 1_000, targets, 200))         // nothing near
        assertEquals(Snapped(0, 0), Snap.move(-150, 1_000, targets, 200))                 // start of timeline
    }

    @Test fun edgeSnapsOnlyWithinThreshold() {
        val targets = Snap.targets(t, "s", 2_500)
        assertEquals(Snapped(2_500, 2_500), Snap.edge(2_600, targets, 150))
        assertEquals(Snapped(2_700, null), Snap.edge(2_700, targets, 150))
    }
}

class CompoundTests {
    private var n = 0
    private val ids: () -> String = { "n${n++}" }

    private fun video(id: String, uri: String, start: Long, end: Long, aspect: Float = 16f / 9f) = Clip(
        id = id, track = Track.MAIN, kind = MediaKind.VIDEO, sourceUri = uri, sourceDurationMs = 60_000,
        sourceStartMs = start, sourceEndMs = end, timelineStartMs = 0, hasAudio = true, aspect = aspect,
    )

    private fun project(): Timeline {
        val main = listOf(
            video("a", "u1", 1_000, 4_000),      // 3 s
            video("b", "u2", 0, 2_000),          // 2 s
            video("c", "u1", 10_000, 15_000),    // 5 s
            video("d", "u3", 0, 1_000),          // 1 s
        )
        val music = Clip(
            id = "m", track = Track.MUSIC, kind = MediaKind.AUDIO, sourceUri = "music", sourceDurationMs = 30_000,
            sourceStartMs = 0, sourceEndMs = 30_000, timelineStartMs = 0, hasAudio = true,
        )
        return TimelineOps.normalize(Timeline(main + music))
    }

    /** What the player sees: (uri, sourceStart, sourceEnd, timelineStart) of every main piece. */
    private fun plays(t: Timeline, aspect: Float = 9f / 16f) =
        Compound.flatten(t, aspect).main.map { listOf(it.sourceUri, it.sourceStartMs, it.sourceEndMs, it.timelineStartMs) }

    @Test
    fun compoundPlaysExactlyLikeTheClipsItWasMadeFrom() {
        val t = project()
        val r = Compound.create(t, listOf("b", "c"), ids, 9f / 16f)
        assertTrue(r.changed)
        assertEquals(3, r.timeline.main.size)
        val compound = r.timeline.main[1]
        assertTrue(compound.isCompound)
        assertTrue(compound.selected)
        assertEquals(7_000L, compound.durationMs)
        assertEquals(3_000L, compound.timelineStartMs)
        assertEquals(t.durationMs, r.timeline.durationMs)
        assertEquals(plays(t), plays(r.timeline))
        assertEquals("music track untouched", t.track(Track.MUSIC), r.timeline.track(Track.MUSIC).map { it.copy(selected = false) })
    }

    @Test
    fun clipsBetweenTheChosenOnesAreIncluded() {
        val r = Compound.create(project(), listOf("d", "a"), ids, 1f)
        assertEquals(1, r.timeline.main.size)
        assertEquals(4, r.timeline.main[0].children.size)
        assertEquals(listOf("a", "b", "c", "d"), r.timeline.main[0].children.map { it.id })
    }

    @Test
    fun oneClipIsNotEnough() {
        val r = Compound.create(project(), listOf("a"), ids, 1f)
        assertFalse(r.changed)
    }

    @Test
    fun trimmingAndSplittingACompoundCutsThroughItsClips() {
        val made = Compound.create(project(), listOf("a", "b", "c", "d"), ids, 1f).timeline
        val c = made.main.single()
        // hide the first 3.5 s (all of a, half a second of b) and the last 1.5 s (d and 0.5 s of c)
        val trimmed = TimelineOps.trimMain(made, c.id, newSourceStartMs = 3_500, newSourceEndMs = 9_500)
        assertEquals(
            listOf(
                listOf("u2", 500L, 2_000L, 0L),
                listOf("u1", 10_000L, 14_500L, 1_500L),
            ),
            plays(trimmed),
        )
        // split at 1 s: both halves still play the same thing
        val split = TimelineOps.split(trimmed, c.id, 1_000, ids)
        assertTrue(split.changed)
        assertEquals(2, split.timeline.main.size)
        assertTrue(split.timeline.main.all { it.isCompound })
        val joined = plays(split.timeline).map { it[0] to (it[2] as Long - (it[1] as Long)) }
        assertEquals(listOf("u2" to 1_000L, "u2" to 500L, "u1" to 4_500L), joined)
        assertEquals(6_000L, split.timeline.durationMs)
    }

    @Test
    fun uncompoundGivesBackTheClipsWithNewIds() {
        val t = project()
        val made = Compound.create(t, listOf("a", "d"), ids, 9f / 16f).timeline
        val back = Compound.uncompound(made, made.main.single().id, ids, 9f / 16f)
        assertTrue(back.changed)
        assertEquals(plays(t), plays(back.timeline))
        assertEquals(4, back.timeline.main.size)
        assertTrue(back.timeline.main.none { it.id in setOf("a", "b", "c", "d") })
        assertTrue(back.timeline.main.none { it.isCompound })
    }

    @Test
    fun nestedCompoundsFlattenAllTheWay() {
        val inner = Compound.create(project(), listOf("a", "b"), ids, 1f).timeline
        val outer = Compound.create(inner, inner.main.map { it.id }, ids, 1f)
        assertTrue(outer.changed)
        val top = outer.timeline.main.single()
        assertEquals(4, Compound.leafCount(top))
        assertEquals(plays(project(), 1f), plays(outer.timeline, 1f))
        val ids = Compound.flatten(outer.timeline, 1f).main.map { it.id }
        assertEquals("piece ids are unique", ids.size, ids.toSet().size)
    }

    @Test
    fun compoundTransformMovesEverythingInside() {
        val aspect = 9f / 16f
        val made = Compound.create(project(), listOf("a", "b"), ids, aspect).timeline
        val c = made.main[0]
        // identity: pieces keep their own framing
        val plain = Compound.flatten(made, aspect).main[0]
        assertEquals(0.5f, plain.posX, 1e-4f)
        assertEquals(TimelineOps.MAIN_SCALE_UNIT, plain.scale, 1e-4f)
        // zoom the compound x2 and move it to the right: the piece doubles and moves with it
        val zoomed = TimelineOps.transformMain(made, c.id, 0.75f, 0.5f, 2f, 90f)
        val p = Compound.flatten(zoomed, aspect).main[0]
        assertEquals(2f, TimelineOps.mainZoom(p), 1e-3f)
        assertEquals(0.75f, p.posX, 1e-4f)
        assertEquals(0.5f, p.posY, 1e-4f)
        assertEquals(90f, p.rotation, 1e-3f)
    }

    @Test
    fun offCentreChildRotatesAroundTheCompoundCentre() {
        val aspect = 1f
        val made = Compound.create(project(), listOf("a", "b"), ids, aspect).timeline
        val c = made.main[0]
        // put the first child at the right edge inside the compound
        val moved = made.copy(clips = made.clips.map {
            if (it.id == c.id) it.copy(children = it.children.mapIndexed { i, ch -> if (i == 0) ch.copy(posX = 1f) else ch }) else it
        })
        val turned = TimelineOps.transformMain(moved, c.id, 0.5f, 0.5f, 1f, 90f)
        val p = Compound.flatten(turned, aspect).main[0]
        // right edge turned a quarter clockwise (screen y down) ends at the bottom centre
        assertEquals(0.5f, p.posX, 1e-4f)
        assertEquals(1f, p.posY, 1e-4f)
    }

    @Test
    fun duplicateMainPutsTheCopyRightAfter() {
        val t = TimelineOps.duplicateMain(project(), "b", ids)
        assertEquals(listOf("a", "b", "n0", "c", "d"), t.main.map { it.id })
        assertTrue(t.byId("n0")!!.selected)
        assertEquals(project().durationMs + 2_000, t.durationMs)
        assertEquals(t.main[1].copy(id = "n0", timelineStartMs = 5_000, selected = true), t.main[2])
    }

    @Test
    fun pasteInsertsAtThePlayheadAndSplitsTheClipThere() {
        val copied = project().byId("d")!!
        val t = TimelineOps.paste(project(), copied, 6_000, ids)
        // c (5..10 s) is split at 6 s and the pasted clip goes in between
        assertEquals(listOf("a", "b", "c", "n0", "n1", "d"), t.main.map { it.id })
        assertEquals(6_000L, t.byId("n0")!!.timelineStartMs)
        assertTrue(t.byId("n0")!!.selected)
        assertEquals(project().durationMs + 1_000, t.durationMs)
        val music = project().byId("m")!!
        val t2 = TimelineOps.paste(project(), music, 4_000, ids)
        assertEquals(2, t2.track(Track.MUSIC).size)
        assertEquals(4_000L, t2.track(Track.MUSIC).last().timelineStartMs)
    }

    @Test
    fun libraryIdsInsideACompoundAreFound() {
        val t = TimelineOps.normalize(Timeline(listOf(video("a", "u", 0, 1000), video("b", "fx", 0, 1000).copy(libraryItemId = 7))))
        val c = Compound.create(t, listOf("a", "b"), ids, 1f).timeline.main.single()
        assertEquals(setOf(7L), Compound.libraryIds(c))
    }
}

class MultiTrackCompoundTests {
    private var n = 0
    private val ids: () -> String = { "n${n++}" }
    private val aspect = 9f / 16f

    private fun clip(id: String, track: Track, kind: MediaKind, uri: String, start: Long, end: Long, at: Long, layer: Int = 0) = Clip(
        id = id, track = track, kind = kind, sourceUri = uri, sourceDurationMs = 60_000, sourceStartMs = start,
        sourceEndMs = end, timelineStartMs = at, hasAudio = kind != MediaKind.IMAGE, aspect = 1f, layer = layer,
    )

    /** Main 0-3 s, 3-5 s, 5-10 s; overlay 2-4 s; image overlay 6-8 s; sound 4-6 s; music 1-12 s. */
    private fun project(): Timeline {
        val main = listOf(
            clip("a", Track.MAIN, MediaKind.VIDEO, "va", 0, 3_000, 0),
            clip("b", Track.MAIN, MediaKind.VIDEO, "vb", 0, 2_000, 0),
            clip("c", Track.MAIN, MediaKind.VIDEO, "vc", 0, 5_000, 0),
        )
        val free = listOf(
            clip("ov", Track.OVERLAY, MediaKind.VIDEO, "ov", 10_000, 12_000, 2_000, layer = 0).copy(posX = 0.3f, posY = 0.7f, scale = 0.5f),
            clip("img", Track.OVERLAY, MediaKind.IMAGE, "img", 0, 2_000, 6_000, layer = 1).copy(sourceDurationMs = 0, scale = 0.2f),
            clip("sfx", Track.SFX, MediaKind.AUDIO, "sfx", 500, 2_500, 4_000),
            clip("mus", Track.MUSIC, MediaKind.AUDIO, "mus", 0, 11_000, 1_000),
        )
        return TimelineOps.normalize(Timeline(main + free))
    }

    /** What plays where: every piece's track, file, source range and start (plus overlay framing). */
    private fun plays(t: Timeline): List<String> {
        val flat = Compound.flatten(t, aspect)
        // audio / overlays cut into several pieces count by covered source time
        return flat.clips.flatMap { c ->
            val step = 250L
            ((c.timelineStartMs + 249) / 250 * 250 until c.timelineEndMs step step).map { ms ->
                val frame = if (c.track == Track.OVERLAY) String.format("%.3f/%.3f/%.3f", c.posX, c.posY, c.scale) else ""
                "${c.track}|${c.sourceUri}|$ms|${c.timelineToSource(ms)}|$frame"
            }
        }.sorted()
    }

    @Test
    fun mainOverlaySoundAndMusicGoIntoOneCompoundAndPlayTheSame() {
        val t = project()
        val r = Compound.create(t, listOf("a", "c", "ov", "img", "sfx", "mus"), ids, aspect)
        assertTrue(r.changed)
        val main = r.timeline.main.single()
        assertTrue(main.isCompound)
        assertEquals(mapOf(Track.MAIN to 3, Track.OVERLAY to 2, Track.SFX to 1, Track.MUSIC to 1), Compound.leafTracks(main))
        // the music ran on past the end of the main track: that part stays on the music track
        val music = r.timeline.track(Track.MUSIC)
        assertEquals(1, music.size)
        assertEquals(10_000L, music.single().timelineStartMs)
        assertEquals(plays(t), plays(r.timeline))
        assertTrue(r.timeline.track(Track.OVERLAY).isEmpty())
    }

    @Test
    fun overlayAndSoundOnlyMakeACompoundOnTheOverlayTrack() {
        val t = project()
        val r = Compound.create(t, listOf("ov", "img", "sfx"), ids, aspect)
        assertTrue(r.changed)
        val c = r.timeline.track(Track.OVERLAY).single()
        assertTrue(c.isCompound)
        assertEquals(2_000L, c.timelineStartMs)
        assertEquals(6_000L, c.durationMs)
        assertTrue(r.timeline.track(Track.SFX).isEmpty())
        assertEquals(3, r.timeline.main.size)
        assertEquals(plays(t), plays(r.timeline))
        // move it: everything inside moves with it
        val moved = TimelineOps.move(r.timeline, c.id, 3_000)
        val flat = Compound.flatten(moved, aspect)
        assertEquals(5_000L, flat.track(Track.SFX).single().timelineStartMs)
        assertEquals(3_000L, flat.track(Track.OVERLAY).first { it.sourceUri == "ov" }.timelineStartMs)
    }

    @Test
    fun soundsOnlyStayOnASoundTrack() {
        val r = Compound.create(project(), listOf("sfx", "mus"), ids, aspect)
        assertTrue(r.changed)
        assertTrue(r.timeline.track(Track.SFX).single().isCompound)
        assertTrue(r.timeline.track(Track.MUSIC).isEmpty())
        assertEquals(plays(project()), plays(r.timeline))
    }

    @Test
    fun moreClipsCanBeAddedToAnExistingCompound() {
        val first = Compound.create(project(), listOf("a", "b", "c", "ov"), ids, aspect).timeline
        val compound = first.main.first { it.isCompound }
        assertEquals(4, Compound.leafCount(compound))
        val more = Compound.create(first, listOf(compound.id, "img", "sfx"), ids, aspect)
        assertTrue(more.changed)
        val joined = more.timeline.main.single { it.isCompound }
        assertEquals("one compound, not one inside another", 6, joined.children.size)
        assertEquals(1, more.timeline.main.size)
        assertTrue(joined.children.none { it.isCompound })
        assertEquals(plays(project()), plays(more.timeline))
    }

    @Test
    fun uncompoundPutsOverlaysAndSoundsBackOnTheirTracks() {
        val made = Compound.create(project(), listOf("a", "b", "c", "ov", "img", "sfx"), ids, aspect).timeline
        val back = Compound.uncompound(made, made.main.single().id, ids, aspect)
        assertTrue(back.changed)
        assertEquals(3, back.timeline.main.size)
        assertEquals(2, back.timeline.track(Track.OVERLAY).size)
        assertEquals(1, back.timeline.track(Track.SFX).size)
        assertTrue(back.timeline.clips.none { it.isCompound })
        assertEquals(plays(project()), plays(back.timeline))
        // overlay order kept: the image was above the video overlay
        val ov = back.timeline.track(Track.OVERLAY).sortedBy { it.layer }
        assertEquals(listOf("ov", "img"), ov.map { it.sourceUri })
    }

    @Test
    fun splittingACompoundDoesNotPlayItsMusicTwice() {
        val made = Compound.create(project(), listOf("a", "c", "mus"), ids, aspect).timeline
        val c = made.main.single()
        val split = TimelineOps.split(made, c.id, 4_000, ids)
        assertTrue(split.changed)
        assertEquals(plays(made), plays(split.timeline))
        val musicPieces = Compound.flatten(split.timeline, aspect).track(Track.MUSIC)
        assertEquals(11_000L, musicPieces.sumOf { it.durationMs })
    }

    @Test
    fun movingACompoundOnTheMainTrackTakesItsOverlaysAlong() {
        val t = TimelineOps.normalize(Timeline(project().clips + clip("d", Track.MAIN, MediaKind.VIDEO, "vd", 0, 4_000, 99_999)))
        val made = Compound.create(t, listOf("a", "ov"), ids, aspect).timeline
        val c = made.main.first()
        val moved = TimelineOps.moveMain(made, c.id, 1)
        val flat = Compound.flatten(moved, aspect)
        // a (3 s) now starts after b (2 s); the overlay was 2 s into a -> starts at 4 s
        assertEquals(2_000L, flat.main.first { it.sourceUri == "va" }.timelineStartMs)
        // the piece inside the compound (the part after 3 s stayed on the overlay track)
        val ovPiece = flat.track(Track.OVERLAY).first { it.sourceUri == "ov" && '/' in it.id }
        assertEquals(4_000L, ovPiece.timelineStartMs)
        assertEquals(1_000L, ovPiece.durationMs)
    }

    @Test
    fun overlayInsideACompoundFollowsItsFraming() {
        val made = Compound.create(project(), listOf("a", "b", "c", "ov"), ids, aspect).timeline
        val c = made.main.single()
        val zoomed = TimelineOps.transformMain(made, c.id, 0.5f, 0.5f, 2f, 0f)
        val ov = Compound.flatten(zoomed, aspect).track(Track.OVERLAY).single { it.sourceUri == "ov" }
        assertEquals(1f, ov.scale, 1e-4f)                  // 0.5 of the frame width, zoomed x2
        assertEquals(0.5f + (0.3f - 0.5f) * 2f, ov.posX, 1e-4f)
        assertEquals(0.5f + (0.7f - 0.5f) * 2f, ov.posY, 1e-4f)
        assertTrue("inside the main video: below the project's own overlays", ov.layer < 0)
    }

    @Test
    fun trimmingAnOverlayCompoundTrimsWhatIsInside() {
        val made = Compound.create(project(), listOf("ov", "img"), ids, aspect).timeline
        val c = made.track(Track.OVERLAY).single()
        // compound runs 2-8 s; cut it to start at 5 s: the video overlay (2-4 s) disappears
        val trimmed = TimelineOps.trimStart(made, c.id, 5_000)
        val flat = Compound.flatten(trimmed, aspect).track(Track.OVERLAY)
        assertEquals(listOf("img"), flat.map { it.sourceUri })
        assertEquals(6_000L, flat.single().timelineStartMs)
    }
}

class CaptionTests {
    private var n = 0
    private val ids: () -> String = { "c${n++}" }

    @Test
    fun speechBecomesCaptionsThatNeverOverlap() {
        val pieces = listOf(
            SpeechPiece(500, 1_800, " আমি আজকে "),
            SpeechPiece(1_700, 2_000, "একটা"),          // starts before the previous one ends
            SpeechPiece(2_000, 2_100, "ভিডিও"),          // too short: shown at least 400 ms
            SpeechPiece(4_000, 4_600, "[Music]"),        // noise
            SpeechPiece(5_000, 20_000, "অনেক লম্বা"),    // too long: at most 7 s
            SpeechPiece(30_000, 31_000, "after the end"),
        )
        val clips = Captions.build(pieces, projectMs = 25_000, newId = ids)
        assertEquals(listOf("আমি আজকে", "একটা", "ভিডিও", "অনেক লম্বা"), clips.map { it.text })
        assertTrue(clips.all { it.track == Track.TEXT && it.kind == MediaKind.TEXT && it.name == Captions.AUTO_NAME })
        for (i in 1 until clips.size) assertTrue("no overlap", clips[i].timelineStartMs >= clips[i - 1].timelineEndMs)
        assertEquals(1_700L, clips[0].timelineEndMs)
        assertEquals(1_700L, clips[1].timelineStartMs)
        assertEquals(400L, clips[2].durationMs)
        assertEquals(7_000L, clips[3].durationMs)
        assertEquals(1, TimelineOps.lanes(clips).values.toSet().size)
    }

    @Test
    fun captionsStartWhenTheWordsAreSaid() {
        // the recogniser put the first piece at 0 s, but the voice starts at 1.5 s
        val pieces = listOf(SpeechPiece(0, 3_900, "Hello everyone"), SpeechPiece(3_900, 7_400, "make a video"), SpeechPiece(9_000, 9_500, "no voice here"))
        val speech = listOf(MsRange(1_500, 3_700), MsRange(4_100, 6_000))
        val aligned = Captions.alignToSpeech(pieces, speech)
        assertEquals(SpeechPiece(1_380, 3_900, "Hello everyone"), aligned[0])
        assertEquals(SpeechPiece(3_980, 6_250, "make a video"), aligned[1])
        assertEquals("no detected speech: unchanged", pieces[2], aligned[2])
    }

    @Test
    fun soundIsCutIntoShortPartsAtPauses() {
        val quiet: (Long, Long) -> Long = { from, to -> (from + to) / 2 }
        // two sentences with a pause, one long stretch without a pause, silence in between
        val speech = listOf(MsRange(1_000, 5_000), MsRange(6_000, 10_000), MsRange(30_000, 70_000))
        val parts = Captions.planChunks(80_000, speech, quiet)
        assertTrue("every part short enough", parts.all { it.lengthMs <= 15_000 })
        assertEquals(MsRange(700, 10_300), parts.first())
        assertTrue("the silence 10-30 s is skipped", parts.none { it.startMs < 29_000 && it.endMs > 11_000 })
        // the long stretch is covered without gaps
        val long = parts.filter { it.startMs >= 29_000 }
        assertEquals(29_700L, long.first().startMs)
        assertEquals(70_300L, long.last().endMs)
        for (i in 1 until long.size) assertEquals(long[i - 1].endMs, long[i].startMs)
        // nothing known about speech: the whole sound in parts
        val blind = Captions.planChunks(40_000, emptyList(), quiet)
        assertEquals(0L, blind.first().startMs)
        assertEquals(40_000L, blind.last().endMs)
        assertTrue(blind.all { it.lengthMs <= 15_000 })
    }

    @Test
    fun noiseIsRecognised() {
        assertTrue(Captions.isNoise(" (music) "))
        assertTrue(Captions.isNoise("♪♪"))
        assertTrue(Captions.isNoise("।"))
        assertFalse(Captions.isNoise("ধন্যবাদ"))
        assertFalse(Captions.isNoise("Hello (again) world"))
    }

    @Test
    fun newAutoCaptionsReplaceTheOldOnesButKeepTypedText() {
        val main = Clip(id = "m", track = Track.MAIN, kind = MediaKind.VIDEO, sourceUri = "v", sourceDurationMs = 10_000,
            sourceStartMs = 0, sourceEndMs = 10_000, timelineStartMs = 0, hasAudio = true)
        val typed = Captions.textClip("typed", 0, 2_000, "Title")
        val old = Captions.build(listOf(SpeechPiece(0, 1_000, "old")), 10_000, ids)
        val t = Timeline(listOf(main, typed) + old)
        val fresh = Captions.build(listOf(SpeechPiece(2_000, 3_000, "new")), 10_000, ids)
        val r = Captions.replaceAuto(t, fresh)
        assertEquals(listOf("Title", "new"), r.track(Track.TEXT).map { it.text })
    }

    @Test
    fun textClipsTrimAndSplitLikeImages() {
        val t = Timeline(listOf(Captions.textClip("t", 1_000, 3_000, "hello")))
        val trimmed = TimelineOps.trimStart(t, "t", 2_000)
        assertEquals(2_000L, trimmed.byId("t")!!.timelineStartMs)
        assertEquals(2_000L, trimmed.byId("t")!!.durationMs)
        val longer = TimelineOps.trimEnd(t, "t", 9_000)
        assertEquals(8_000L, longer.byId("t")!!.durationMs)
        val split = TimelineOps.split(t, "t", 2_500, ids)
        assertEquals(listOf(1_500L, 1_500L), split.timeline.track(Track.TEXT).map { it.durationMs })
        assertTrue(split.timeline.track(Track.TEXT).all { it.text == "hello" })
    }

    @Test
    fun captionsGoIntoCompoundsAndFollowTheirFraming() {
        val main = Clip(id = "m", track = Track.MAIN, kind = MediaKind.VIDEO, sourceUri = "v", sourceDurationMs = 10_000,
            sourceStartMs = 0, sourceEndMs = 10_000, timelineStartMs = 0, hasAudio = true)
        val cap = Captions.textClip("cap", 2_000, 1_000, "hi").copy(posX = 0.5f, posY = 0.8f)
        val made = Compound.create(TimelineOps.normalize(Timeline(listOf(main, cap))), listOf("m", "cap"), ids, 1f).timeline
        assertTrue(made.track(Track.TEXT).isEmpty())
        val c = made.main.single()
        val zoomed = TimelineOps.transformMain(made, c.id, 0.5f, 0.5f, 2f, 0f)
        val piece = Compound.flatten(zoomed, 1f).track(Track.TEXT).single()
        assertEquals("hi", piece.text)
        assertEquals(2_000L, piece.timelineStartMs)
        assertEquals(0.5f + 0.3f * 2f, piece.posY, 1e-4f)
        // text-only picks make a compound on the text track
        val two = Timeline(listOf(main, cap, Captions.textClip("cap2", 4_000, 1_000, "there")))
        val textOnly = Compound.create(two, listOf("cap", "cap2"), ids, 1f).timeline
        assertTrue(textOnly.track(Track.TEXT).single().isCompound)
    }
}
