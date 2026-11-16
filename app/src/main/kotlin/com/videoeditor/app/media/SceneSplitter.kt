package com.videoeditor.app.media

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.videoeditor.core.SceneDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * "Split scenes": looks at every frame of a part of a video (decoded on the phone, each frame
 * shrunk to a tiny brightness picture) and returns where the shots change ([SceneDetector]).
 */
object SceneSplitter {
    class Failed(message: String) : Exception(message)

    /** For tests / reports: frames looked at in the last run. */
    @Volatile
    var lastFrames = 0
        private set

    /**
     * The cuts between [startMs] and [endMs] of [uri], as times in the file (ms): each is the
     * first frame of a new shot.
     */
    suspend fun findCuts(context: Context, uri: Uri, startMs: Long, endMs: Long, onProgress: (Float) -> Unit): List<Long> =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                try {
                    extractor.setDataSource(context, uri, null)
                } catch (e: Exception) {
                    throw Failed("This video cannot be opened.")
                }
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: throw Failed("This clip has no picture to look at.")
                val format = extractor.getTrackFormat(track)
                extractor.selectTrack(track)
                extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                // as fast as the decoder can, not at playing speed
                format.setInteger(MediaFormat.KEY_PRIORITY, 1)
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, 960)
                val decoder = try {
                    MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also {
                        codec = it
                        it.configure(format, null, null, 0)
                        it.start()
                    }
                } catch (e: Exception) {
                    throw Failed("This phone cannot read this kind of video for Split scenes.")
                }
                val detector = SceneDetector()
                val luma = ByteArray(SceneDetector.W * SceneDetector.H)
                val info = MediaCodec.BufferInfo()
                val startUs = startMs * 1000
                val endUs = endMs * 1000
                var inputDone = false
                var done = false
                var frames = 0
                var lastProgress = -1f
                var idle = 0
                while (!done) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val i = decoder.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val size = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                            if (size < 0 || extractor.sampleTime > endUs + 500_000) {
                                decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = decoder.dequeueOutputBuffer(info, 5_000)
                    if (o >= 0) {
                        idle = 0
                        val pts = info.presentationTimeUs
                        if (info.size > 0 && pts >= startUs && pts < endUs) {
                            val image = decoder.getOutputImage(o)
                            if (image != null) {
                                try {
                                    shrink(image, luma)
                                } finally {
                                    image.close()
                                }
                                detector.feed(pts / 1000, luma)
                                frames++
                            }
                            val p = ((pts - startUs).toFloat() / (endUs - startUs).coerceAtLeast(1)).coerceIn(0f, 1f)
                            if (p - lastProgress >= 0.01f) {
                                lastProgress = p
                                onProgress(p)
                            }
                        }
                        decoder.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || pts >= endUs) done = true
                    } else if (inputDone && ++idle > 400) {
                        done = true          // the decoder has nothing more (2 s of silence after the end)
                    }
                }
                lastFrames = frames
                if (frames < 2) throw Failed("The picture of this clip could not be read.")
                onProgress(1f)
                detector.finish(endMs)
            } catch (e: Failed) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("SceneSplitter", "scene detection failed", e)
                throw Failed("The scenes of this video could not be read.")
            } finally {
                try {
                    codec?.stop()
                } catch (e: Exception) {
                }
                try {
                    codec?.release()
                } catch (e: Exception) {
                }
                extractor.release()
            }
        }

    /** The brightness of the picture as [SceneDetector.W] x [SceneDetector.H] averages (3 x 3 points per cell). */
    private fun shrink(image: Image, out: ByteArray) {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        // 10-bit pictures (2 bytes per value, little endian): the high byte
        val high = if (pixelStride >= 2 && image.format != android.graphics.ImageFormat.YUV_420_888) pixelStride - 1 else 0
        val crop = image.cropRect
        val w = crop.width()
        val h = crop.height()
        val limit = buf.limit()
        var k = 0
        for (cy in 0 until SceneDetector.H) for (cx in 0 until SceneDetector.W) {
            var sum = 0
            var n = 0
            for (sy in 0 until 3) for (sx in 0 until 3) {
                val x = crop.left + ((cx * 3 + sx) * 2 + 1) * w / (SceneDetector.W * 6)
                val y = crop.top + ((cy * 3 + sy) * 2 + 1) * h / (SceneDetector.H * 6)
                val at = y * rowStride + x * pixelStride + high
                if (at < limit) {
                    sum += buf.get(at).toInt() and 0xFF
                    n++
                }
            }
            out[k++] = (if (n == 0) 0 else sum / n).toByte()
        }
    }
}
