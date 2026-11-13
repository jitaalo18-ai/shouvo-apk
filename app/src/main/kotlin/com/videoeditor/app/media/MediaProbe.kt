package com.videoeditor.app.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.videoeditor.core.MediaKind

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
