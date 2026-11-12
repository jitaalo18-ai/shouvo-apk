package com.videoeditor.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.videoeditor.app.media.Thumbnails
import com.videoeditor.core.MediaKind
import java.util.Locale

val Bg = Color(0xFF0E0F12)
val Panel = Color(0xFF1B1D23)
val Accent = Color(0xFF4F7CFF)
val OverlayColor = Color(0xFF8E5BD9)
val SfxColor = Color(0xFF2E9E7A)
val MusicColor = Color(0xFFC77A2B)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = Bg,
            surface = Bg,
            surfaceVariant = Panel,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        content = content,
    )
}

/** Keeps read access to a picked file across app restarts (project media is referenced, not copied). */
fun persistRead(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: Exception) {
        // Not every provider offers persistable permissions; the URI still works for this session.
    }
}

fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val tenths = (ms.coerceAtLeast(0) % 1000) / 100
    return String.format(Locale.US, "%02d:%02d.%d", total / 60, total % 60, tenths)
}

/** Video frame / image thumbnail, loaded off the main thread and cached. */
@Composable
fun Thumb(
    uri: String,
    kind: MediaKind,
    timeMs: Long,
    modifier: Modifier = Modifier,
    maxSize: Int = 160,
    contentScale: ContentScale = ContentScale.Crop,
    background: Color = Color(0xFF22252C),
) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(initialValue = null, uri, timeMs / 1000, maxSize) {
        value = Thumbnails.load(context, uri, kind, timeMs, maxSize)
    }
    Box(modifier.background(background)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
