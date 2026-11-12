package com.videoeditor.app.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import android.webkit.MimeTypeMap
import com.videoeditor.core.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaInfo(
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val kind: MediaKind,
    val durationMs: Long,
    val hasAudio: Boolean,
    /** Display width / height (rotation applied). 1 for audio. */
    val aspect: Float,
)

/** Media Import: reads what a picked file is, without modifying it. */
object MediaProbe {
    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
                }
            } catch (e: Exception) {
                // fall through
            }
        }
        return uri.lastPathSegment ?: "media"
    }

    fun mimeType(context: Context, uri: Uri, name: String): String? {
        context.contentResolver.getType(uri)?.let { return it }
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    fun probe(context: Context, uri: Uri): MediaInfo? {
        val name = displayName(context, uri)
        val mime = mimeType(context, uri, name)
        if (mime != null && mime.startsWith("image/")) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: Exception) {
                return null
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            return MediaInfo(uri, name, mime, MediaKind.IMAGE, 0, false, opts.outWidth.toFloat() / opts.outHeight)
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (duration <= 0 || (!hasVideo && !hasAudio)) return null
            var aspect = 1f
            if (hasVideo) {
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) aspect = if (rot == 90 || rot == 270) h / w else w / h
            }
            MediaInfo(uri, name, mime, if (hasVideo) MediaKind.VIDEO else MediaKind.AUDIO, duration, hasAudio, aspect)
        } catch (e: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}

/** Small in-memory thumbnail cache for the timeline, library and project list. */
object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val io = Dispatchers.IO.limitedParallelism(2)

    /** A frame of a video (at [timeMs], rounded to the second) or a scaled-down image. */
    suspend fun load(context: Context, uri: String, kind: MediaKind, timeMs: Long, maxSize: Int = 160): Bitmap? {
        val second = timeMs / 1000
        val key = "$uri@$second@$maxSize"
        cache.get(key)?.let { return it }
        val bmp = withContext(io) {
            try {
                when (kind) {
                    MediaKind.IMAGE -> decodeImage(context, Uri.parse(uri), maxSize)
                    MediaKind.VIDEO -> videoFrame(context, Uri.parse(uri), second * 1000, maxSize, exact = false)
                    MediaKind.AUDIO -> null
                }
            } catch (e: Exception) {
                null
            }
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    /**
     * A thumbnail that shows what a video looks like. Many clips start with a black frame, so
     * exact frames from the middle of the clip are tried and the first clearly visible one (or
     * else the brightest) is used.
     */
    suspend fun representative(context: Context, uri: String, durationMs: Long, maxSize: Int = 160): Bitmap? {
        val key = "$uri@rep@$maxSize"
        cache.get(key)?.let { return it }
        val bmp = withContext(io) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, Uri.parse(uri))
                var best: Bitmap? = null
                var bestBrightness = -1f
                for (fraction in floatArrayOf(0.5f, 0.25f, 0.75f, 0.1f)) {
                    val timeUs = (durationMs * fraction).toLong() * 1000
                    val frame = r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, maxSize, maxSize)
                        ?: continue
                    val b = brightness(frame)
                    if (b > bestBrightness) {
                        best = frame
                        bestBrightness = b
                    }
                    if (b > 40f) break
                }
                best
            } catch (e: Exception) {
                null
            } finally {
                try {
                    r.release()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    /** Average brightness (0..255) of a bitmap, sampled on an 8 x 8 grid. */
    private fun brightness(bitmap: Bitmap): Float {
        var sum = 0L
        var count = 0
        for (gy in 0 until 8) {
            for (gx in 0 until 8) {
                val x = (bitmap.width * (gx + 0.5f) / 8f).toInt().coerceIn(0, bitmap.width - 1)
                val y = (bitmap.height * (gy + 0.5f) / 8f).toInt().coerceIn(0, bitmap.height - 1)
                val c = bitmap.getPixel(x, y)
                sum += ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                count += 3
            }
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    fun decodeImage(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = maxSize.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        }
    }

    fun videoFrame(context: Context, uri: Uri, timeMs: Long, maxSize: Int, exact: Boolean): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            r.getScaledFrameAtTime(timeMs * 1000, option, maxSize, maxSize)
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
