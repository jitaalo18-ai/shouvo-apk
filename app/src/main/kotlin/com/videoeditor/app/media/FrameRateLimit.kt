package com.videoeditor.app.media

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import com.videoeditor.core.FrameRateLimiter

/**
 * Export at a chosen frame rate: passes on the frames [FrameRateLimiter] keeps and leaves the
 * others out (Media3's own FrameDropEffect drops nothing going from 30 to 24 or 25 fps).
 */
class FrameRateLimit(private val fps: Int) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(fps)

    private class Program(fps: Int) : PassthroughShaderProgram() {
        private val limiter = FrameRateLimiter(fps)

        override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
            if (limiter.keep(presentationTimeUs)) {
                super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs)
            } else {
                // left out: hand the frame back and ask for the next one
                inputListener.onInputFrameProcessed(inputTexture)
                inputListener.onReadyToAcceptInputFrame()
            }
        }

        override fun flush() {
            limiter.reset()
            super.flush()
        }
    }
}
