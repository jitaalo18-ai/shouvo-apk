package com.videoeditor.app

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.media.FxState
import com.videoeditor.app.media.VoiceFxSink
import com.videoeditor.core.AudioFx
import com.videoeditor.core.FxSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * The sound sink keeps the rule of the sink below it (DefaultAudioSink): a buffer it did not take
 * completely must be handed again, the very same buffer, until it is taken. 0.18 broke the rule
 * when an effect was switched on while a buffer was half taken - the player stopped with
 * "IllegalArgumentException at DefaultAudioSink.handleBuffer" (the video "paused"). Here effects
 * are switched on and off at random moments, also between the retries of one buffer, the sink
 * below takes random parts, and nothing may break and no sound may get lost.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSinkContractTest {

    /** Takes random parts of what it gets and checks the same-buffer rule, like DefaultAudioSink. */
    private class StrictSink(context: android.content.Context) : ForwardingAudioSink(DefaultAudioSink.Builder(context).build()) {
        val rnd = Random(3)
        var held: ByteBuffer? = null
        var taken = 0L
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {}
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            require(held == null || held === buffer) { "a different buffer while one is half taken" }
            held = buffer
            val take = if (rnd.nextInt(3) == 0) 0 else minOf(buffer.remaining(), 2 * (1 + rnd.nextInt(800)))
            buffer.position(buffer.position() + take)
            taken += take
            if (buffer.hasRemaining()) return false
            held = null
            return true
        }
        override fun flush() {
            held = null
        }
    }

    @Test fun switchingEffectsAtAnyMomentNeverBreaksThePlayer() {
        val ctx = Fixtures.target
        val strict = StrictSink(ctx)
        val state = FxState(sourceTime = true)
        val sink = VoiceFxSink(strict, state)
        val rate = 48_000
        sink.configure(
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setChannelCount(2).setSampleRate(rate).build(), 0, null,
        )
        val on = listOf(FxSegment("c", 0, Long.MAX_VALUE / 4, AudioFx(volume = 0.8f, enhance = 0.5f)))
        val rnd = Random(7)
        var pts = 0L
        var given = 0L
        var switches = 0
        for (n in 0 until 3_000) {
            val frames = 256 + rnd.nextInt(2048)
            val buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until frames * 2) buf.putShort((rnd.nextGaussian() * 3000).toInt().toShort())
            buf.flip()
            given += frames * 4
            var tries = 0
            while (true) {
                // effects switch on / off at random, also while this buffer is half taken
                if (rnd.nextInt(4) == 0) {
                    state.plan = if (state.active) emptyList() else on
                    switches++
                }
                if (sink.handleBuffer(buf, pts, frames)) break
                assertTrue("stuck", ++tries < 10_000)
            }
            assertEquals("the decoder's buffer is used up", 0, buf.remaining())
            pts += frames * 1_000_000L / rate
            if (n % 500 == 499) sink.flush()          // a seek now and then
        }
        Report.note("voice sink: 3000 buffers, $switches switches, every byte passed on: ${strict.taken} of $given")
        assertEquals("as much sound out as in", given, strict.taken)
    }
}
