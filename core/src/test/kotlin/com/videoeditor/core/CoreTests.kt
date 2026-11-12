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
