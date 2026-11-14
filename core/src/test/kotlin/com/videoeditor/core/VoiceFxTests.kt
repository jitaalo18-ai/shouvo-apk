package com.videoeditor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/** Runs [input] through [fx] in uneven blocks, positions counted from [startUs]. */
private fun run(fx: VoiceFx, input: FloatArray, startUs: Long = 0, seed: Long = 1): FloatArray {
    val ch = fx.channels
    val frames = input.size / ch
    val rnd = Random(seed)
    val out = ArrayList<FloatArray>()
    var at = 0
    while (at < frames) {
        val take = minOf(frames - at, 1 + rnd.nextInt(3000))
        val block = input.copyOfRange(at * ch, (at + take) * ch)
        out += fx.process(block, take, startUs + at * 1_000_000L / fx.sampleRate)
        at += take
    }
    out += fx.drain()
    val all = FloatArray(out.sumOf { it.size })
    var p = 0
    for (b in out) {
        System.arraycopy(b, 0, all, p, b.size)
        p += b.size
    }
    return all
}

private fun noise(n: Int, amp: Float, seed: Long = 7): FloatArray {
    val r = Random(seed)
    return FloatArray(n) { (r.nextGaussian() * amp).toFloat() }
}

private fun tone(n: Int, rate: Int, hz: Double, amp: Float) = FloatArray(n) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }

/** Power in dB of samples [from, to) (mono). */
private fun db(x: FloatArray, from: Int, to: Int): Double {
    var s = 0.0
    for (i in from until to) s += x[i].toDouble() * x[i]
    return 10 * log10(max(s / (to - from), 1e-20))
}

private fun whole(fx: AudioFx, durUs: Long) = listOf(FxSegment("c", 0, durUs, fx))

class VoiceFxTests {
    private val rate = 48_000

    @Test
    fun neutralGivesTheInputBack() {
        val input = noise(rate * 2, 0.2f)          // 1 s stereo
        val fx = VoiceFx(rate, 2)
        val out = run(fx, input)
        assertEquals(input.size, out.size)
        var worst = 0f
        for (i in input.indices) worst = max(worst, abs(out[i] - input[i]))
        assertTrue("max error $worst", worst < 1e-4f)
    }

    @Test
    fun defaultSettingsAndNoiseReductionOffAreUntouched() {
        val input = noise(rate, 0.3f)
        val fx = VoiceFx(rate, 1).apply { plan = whole(AudioFx(), 1_000_000) }
        val out = run(fx, input)
        for (i in input.indices) assertEquals(input[i], out[i], 1e-4f)
    }

    @Test
    fun volumeScales() {
        val input = noise(rate, 0.3f)
        val fx = VoiceFx(rate, 1).apply { plan = whole(AudioFx(volume = 0.5f), 1_000_000) }
        val out = run(fx, input)
        for (i in input.indices) assertEquals(input[i] * 0.5f, out[i], 1e-4f)
    }

    @Test
    fun fadesRampFromAndToSilence() {
        val input = FloatArray(rate) { 0.5f }        // 1 s DC
        val fx = VoiceFx(rate, 1).apply {
            plan = whole(AudioFx(fadeInMs = 200, fadeOutMs = 400), 1_000_000)
        }
        val out = run(fx, input)
        assertEquals(0f, out[0], 0.01f)
        assertEquals(0.25f, out[rate / 10], 0.01f)          // 100 ms: half way in
        assertEquals(0.5f, out[rate / 2], 0.01f)            // middle: full
        assertEquals(0.25f, out[rate - rate / 5], 0.01f)    // 200 ms before the end: half way out
        assertEquals(0f, out[rate - 1], 0.01f)
    }

    @Test
    fun loudSoundIsLimitedNotClipped() {
        val input = tone(rate, rate, 220.0, 0.95f)
        for (fx in listOf(AudioFx(volume = 2f), AudioFx(enhance = 1f, volume = 2f), AudioFx(enhance = 1f))) {
            val p = VoiceFx(rate, 1).apply { plan = whole(fx, 1_000_000) }
            val out = run(p, input)
            val peak = out.maxOf { abs(it) }
            assertTrue("$fx peak $peak", peak < 1f)
        }
        // volume 2 still makes a quiet sound twice as loud (no limiting below the knee)
        val quiet = tone(rate, rate, 220.0, 0.2f)
        val out = run(VoiceFx(rate, 1).apply { plan = whole(AudioFx(volume = 2f), 1_000_000) }, quiet)
        assertEquals(db(quiet, rate / 4, rate) + 6.02, db(out, rate / 4, rate), 0.1)
    }

    @Test
    fun noiseReductionRemovesSteadyNoiseAndKeepsTheVoice() {
        val seconds = 6
        val n = rate * seconds
        val voice = FloatArray(n)
        // a "voice": harmonic bursts, half a second on, half a second off
        for (i in 0 until n) {
            if ((i / (rate / 2)) % 2 == 0) {
                var s = 0.0
                for (h in 1..6) s += sin(2 * PI * 180.0 * h * i / rate) / h
                voice[i] = (0.15 * s).toFloat()
            }
        }
        val hiss = noise(n, 0.02f)
        val input = FloatArray(n) { voice[it] + hiss[it] }
        for ((strength, minDrop) in listOf(1f to 18.0, AudioFx.AUTO_DENOISE to 12.0)) {
            val out = run(VoiceFx(rate, 1).apply { plan = whole(AudioFx(denoise = strength), seconds * 1_000_000L) }, input)
            // the gaps from 2 s on: noise only
            var noiseIn = 0.0
            var noiseOut = 0.0
            var voiceIn = 0.0
            var voiceOut = 0.0
            for (half in 4 until seconds * 2) {
                val a = half * rate / 2 + rate / 20
                val b = (half + 1) * rate / 2 - rate / 20
                if (half % 2 == 1) {
                    noiseIn += db(input, a, b); noiseOut += db(out, a, b)
                } else {
                    voiceIn += db(voice, a, b); voiceOut += db(out, a, b)
                }
            }
            val gaps = (seconds * 2 - 4) / 2
            val drop = (noiseIn - noiseOut) / gaps
            val voiceChange = (voiceOut - voiceIn) / gaps
            println("denoise $strength: noise -%.1f dB, voice %+.2f dB".format(drop, voiceChange))
            assertTrue("noise only $drop dB less", drop >= minDrop)
            assertTrue("voice changed $voiceChange dB", abs(voiceChange) < 1.5)
        }
    }

    @Test
    fun enhanceBrightensTheVoice() {
        // quiet tones (under the compressor): presence goes up more than the low end
        val low = tone(rate, rate, 150.0, 0.01f)
        val mid = tone(rate, rate, 3000.0, 0.01f)
        val e = AudioFx(enhance = 1f)
        val lowOut = run(VoiceFx(rate, 1).apply { plan = whole(e, 1_000_000) }, low)
        val midOut = run(VoiceFx(rate, 1).apply { plan = whole(e, 1_000_000) }, mid)
        val lowGain = db(lowOut, rate / 2, rate) - db(low, rate / 2, rate)
        val midGain = db(midOut, rate / 2, rate) - db(mid, rate / 2, rate)
        println("enhance: 150 Hz %+.1f dB, 3 kHz %+.1f dB".format(lowGain, midGain))
        assertTrue(midGain > lowGain + 3)
        assertTrue(midGain > 6)
        // 30 Hz rumble is cut
        val rumble = tone(rate, rate, 30.0, 0.01f)
        val rOut = run(VoiceFx(rate, 1).apply { plan = whole(e, 1_000_000) }, rumble)
        assertTrue(db(rOut, rate / 2, rate) < db(rumble, rate / 2, rate) - 6)
    }

    @Test
    fun eachSegmentGetsItsOwnSettings() {
        val input = FloatArray(rate) { 0.4f }
        val fx = VoiceFx(rate, 1).apply {
            plan = listOf(
                FxSegment("a", 0, 500_000, AudioFx(volume = 1f)),
                FxSegment("b", 500_000, 1_000_000, AudioFx(volume = 0f)),
            )
        }
        val out = run(fx, input)
        assertEquals(0.4f, out[rate / 4], 1e-4f)
        assertEquals(0f, out[3 * rate / 4], 1e-4f)
        // the boundary is where the positions say (to a sample: positions are whole µs)
        assertEquals(0.4f, out[rate / 2 - 2], 1e-4f)
        assertEquals(0f, out[rate / 2 + 1], 1e-4f)
    }

    @Test
    fun positionsComeFromTheCaller() {
        // the same sound handed in with positions 10 s later lands in the later segment
        val input = FloatArray(rate) { 0.4f }
        val fx = VoiceFx(rate, 1).apply {
            plan = listOf(FxSegment("a", 0, 10_000_000, AudioFx()), FxSegment("b", 10_000_000, 11_000_000, AudioFx(volume = 0.5f)))
        }
        val out = run(fx, input, startUs = 10_000_000)
        assertEquals(0.2f, out[rate / 2], 1e-4f)
    }

    @Test
    fun liveModeDelaysByTheLatency() {
        val input = noise(rate, 0.3f)
        val fx = VoiceFx(rate, 1, compensate = false)
        val parts = ArrayList<Float>()
        var at = 0
        while (at < rate) {
            val take = minOf(700, rate - at)
            parts += fx.process(input.copyOfRange(at, at + take), take, at * 1_000_000L / rate).toList()
            at += take
        }
        val out = parts.toFloatArray()
        assertTrue(out.size >= rate - 1024)
        for (i in fx.latency until out.size) assertEquals(input[i - fx.latency], out[i], 1e-4f)
        // settings follow the delayed sound: a volume change at 0.5 s shows up latency samples later
        val fx2 = VoiceFx(rate, 1, compensate = false).apply {
            plan = listOf(FxSegment("a", 0, 500_000, AudioFx()), FxSegment("b", 500_000, 1_000_000, AudioFx(volume = 0f)))
        }
        val dc = FloatArray(rate) { 0.4f }
        val o2 = fx2.process(dc, rate, 0)
        assertEquals(0.4f, o2[rate / 2 + fx2.latency - 1], 1e-4f)
        assertEquals(0f, o2[rate / 2 + fx2.latency], 1e-4f)
    }

    @Test
    fun resetForgetsTheSoundInFlight() {
        val fx = VoiceFx(rate, 1)
        fx.process(noise(5000, 0.3f), 5000, 0)
        fx.reset()
        val input = noise(rate, 0.3f, seed = 3)
        val out = run(fx, input)
        assertEquals(input.size, out.size)
        for (i in input.indices) assertEquals(input[i], out[i], 1e-4f)
    }

    @Test
    fun settingsSurviveTheDatabase() {
        val fx = AudioFx(volume = 1.35f, denoise = 0.7f, enhance = 0.25f, fadeInMs = 500, fadeOutMs = 1200)
        assertEquals(fx, AudioFx.decode(fx.encode()))
        assertEquals("", AudioFx().encode())
        assertEquals(AudioFx.NONE, AudioFx.decode(""))
        assertEquals(AudioFx.NONE, AudioFx.decode("rubbish"))
        assertEquals(2f, AudioFx.decode("9,0,0,0,0").volume)
    }

    @Test
    fun analysisFindsNoisyClipsAndLoudness() {
        val n = rate * 4
        val voice = FloatArray(n)
        for (i in 0 until n) if ((i / (rate / 2)) % 2 == 0) voice[i] = (0.07 * sin(2 * PI * 200.0 * i / rate)).toFloat()
        val clean = FloatArray(n) { voice[it] + (if (it % 2 == 0) 0.0001f else -0.0001f) }
        val noisy = noise(n, 0.01f).let { h -> FloatArray(n) { voice[it] + h[it] } }
        val a = AudioAnalysis.measure(clean, rate)
        val b = AudioAnalysis.measure(noisy, rate)
        println("clean $a, noisy $b")
        assertFalse(a.isNoisy)
        assertTrue(b.isNoisy)
        // voice at about -26 dBFS RMS: auto volume brings it to -20 (about x2)
        val v = AudioAnalysis.autoVolume(a)!!
        assertEquals(2f, v, 0.1f)
        assertNull(AudioAnalysis.autoVolume(AudioAnalysis.measure(FloatArray(n), rate)))
        // a steady tone is not noise (nothing rises above it, and it is not hiss-like)
        val hum = AudioAnalysis.measure(tone(n, rate, 440.0, 0.1f), rate)
        assertFalse(hum.isNoisy)
        assertTrue(hum.flatness < 0.01f)
        assertEquals(1.4f, AudioAnalysis.autoVolume(hum)!!, 0.05f)       // -23 dB RMS -> +3 dB

    }

    @Test
    fun playMapFindsTheClipOfEverySound() {
        val s = 1_000_000L
        val a = PlayMap.Item("a", 0, 5 * s, 2 * s, true)          // source 5..7 s
        val img = PlayMap.Item("img", 2 * s, 0, 1 * s, false)
        val c = PlayMap.Item("c", 3 * s, 0, 2 * s, true)          // source 0..2 s
        val d = PlayMap.Item("d", 5 * s, 5 * s, 2 * s, true)      // the same part of the source as a
        val map = PlayMap().apply { items = listOf(a, img, c, d) }
        val base = 1_000_000_000_000L
        // the queue starts at a (offsets follow ExoPlayer's rule: next = prev + prevEnd - nextStart)
        val offA = base
        val offImg = offA + 7 * s - 0
        val offC = offImg + 1 * s - 0
        val offD = offC + 2 * s - 5 * s
        map.hintUs = 0
        assertEquals(100_000L, map.locate(offA + 5 * s + 100_000, offA))
        assertEquals(3 * s + 500_000, map.locate(offC + 500_000, offC))
        assertEquals(5 * s + 500_000, map.locate(offD + 5 * s + 500_000, offD))
        // after a seek to d the player starts a new queue at d: same source time as a, the hint decides
        map.forget()
        map.hintUs = 5 * s + 200_000
        assertEquals(5 * s + 200_000, map.locate(base + 5 * s + 200_000, base))
        // a new queue the map was not told about is noticed (source time does not fit) and relearned
        map.hintUs = 3 * s + 1_000_000
        assertEquals(3 * s + 1_000_000, map.locate(base + 1_000_000, base))
    }
}

class VoiceEditTests {
    private fun clip(id: String, start: Long, dur: Long, track: Track = Track.MAIN, kind: MediaKind = MediaKind.VIDEO, audio: Boolean = true) = Clip(
        id = id, track = track, kind = kind, sourceUri = "file:///$id.mp4", sourceDurationMs = dur,
        sourceStartMs = 0, sourceEndMs = dur, timelineStartMs = start, hasAudio = audio,
    )

    private val t = Timeline(listOf(
        clip("a", 0, 4000),
        clip("b", 4000, 3000),
        clip("img", 7000, 2000, kind = MediaKind.IMAGE, audio = false),
        clip("silent", 9000, 2000, audio = false),
        clip("sfx", 1000, 1500, track = Track.SFX, kind = MediaKind.AUDIO),
    ))

    @Test
    fun onlyClipsWithSoundAreChanged() {
        assertEquals(listOf("a", "b", "sfx"), VoiceEdits.audible(t).map { it.id })
        val out = VoiceEdits.change(t, setOf("a", "img", "silent"), { it.copy(volume = 0.5f) })
        assertEquals(0.5f, out.byId("a")!!.audio.volume)
        assertEquals(AudioFx.NONE, out.byId("img")!!.audio)
        assertEquals(AudioFx.NONE, out.byId("silent")!!.audio)
    }

    @Test
    fun valuesStayInRangeAndFadesFitTheClip() {
        val out = VoiceEdits.change(t, setOf("b"), { AudioFx(volume = 5f, denoise = 2f, enhance = -1f, fadeInMs = 9000, fadeOutMs = 900) })
        val fx = out.byId("b")!!.audio
        assertEquals(2f, fx.volume)
        assertEquals(1f, fx.denoise)
        assertEquals(0f, fx.enhance)
        assertEquals(1500L, fx.fadeInMs)     // half of 3 s
        assertEquals(900L, fx.fadeOutMs)
        assertEquals(5000L, VoiceEdits.maxFadeMs(clip("long", 0, 60_000)))
    }

    @Test
    fun applyToAllCopiesNoiseAndVoiceOnly() {
        val t2 = VoiceEdits.change(t, setOf("a"), { it.copy(denoise = 0.7f, enhance = 0.3f, volume = 1.5f) })
        val out = VoiceEdits.enhanceAll(t2, t2.byId("a")!!.audio)
        for (id in listOf("a", "b", "sfx")) {
            assertEquals(0.7f, out.byId(id)!!.audio.denoise)
            assertEquals(0.3f, out.byId(id)!!.audio.enhance)
        }
        assertEquals(1f, out.byId("b")!!.audio.volume)      // volume stays per clip
        assertTrue(VoiceEdits.allEnhancedLike(out, out.byId("a")!!.audio))
        assertFalse(VoiceEdits.allEnhancedLike(t2, t2.byId("a")!!.audio))
    }

    @Test
    fun removeNoiseTogglesOnTheNoisyClips() {
        val levels = mapOf(
            "a" to AudioLevel(noiseDb = -40f, speechDb = -22f),     // noisy
            "b" to AudioLevel(noiseDb = -75f, speechDb = -20f),     // clean
            "sfx" to AudioLevel(noiseDb = -45f, speechDb = -18f),   // noisy
        )
        assertEquals(listOf("a", "sfx"), VoiceEdits.noisy(t, levels).map { it.id })
        val (on, n) = VoiceEdits.toggleRemoveNoise(t, levels)
        assertEquals(2, n)
        assertEquals(AudioFx.AUTO_DENOISE, on.byId("a")!!.audio.denoise)
        assertEquals(0f, on.byId("b")!!.audio.denoise)
        assertTrue(VoiceEdits.noiseRemoved(on, levels))
        val (off, _) = VoiceEdits.toggleRemoveNoise(on, levels)
        assertEquals(0f, off.byId("a")!!.audio.denoise)
        assertFalse(VoiceEdits.noiseRemoved(off, levels))
        // nothing noisy: nothing changes
        val (same, zero) = VoiceEdits.toggleRemoveNoise(t, mapOf("b" to levels.getValue("b")))
        assertEquals(0, zero)
        assertEquals(t, same)
    }

    @Test
    fun autoAdjustLevelsTheVoices() {
        val levels = mapOf(
            "a" to AudioLevel(noiseDb = -70f, speechDb = -26f),     // quiet: louder
            "b" to AudioLevel(noiseDb = -70f, speechDb = -14f),     // loud: softer
            "sfx" to AudioLevel(noiseDb = -100f, speechDb = -100f), // silent: unchanged
        )
        val (out, n) = VoiceEdits.autoAdjust(t, levels)
        assertEquals(2, n)
        assertEquals(2f, out.byId("a")!!.audio.volume, 0.05f)
        assertEquals(0.5f, out.byId("b")!!.audio.volume, 0.05f)
        assertEquals(1f, out.byId("sfx")!!.audio.volume)
    }

    @Test
    fun splitKeepsFadesAtTheOuterEnds() {
        val t2 = VoiceEdits.change(t, setOf("a"), { it.copy(fadeInMs = 1000, fadeOutMs = 1500, denoise = 0.5f) })
        var n = 0
        val r = TimelineOps.split(t2, "a", 2000) { "x${n++}" }
        val left = r.timeline.byId("a")!!
        val right = r.timeline.byId("x0")!!
        assertEquals(1000L, left.audio.fadeInMs)
        assertEquals(0L, left.audio.fadeOutMs)
        assertEquals(0L, right.audio.fadeInMs)
        assertEquals(1500L, right.audio.fadeOutMs)
        assertEquals(0.5f, right.audio.denoise)
    }
}
