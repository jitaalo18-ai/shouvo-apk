package com.videoeditor.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.videoeditor.app.media.Thumbnails
import com.videoeditor.core.MediaKind
import java.util.Locale

val Bg = Color(0xFF0E0F12)
val Panel = Color(0xFF1B1D23)
val Accent = Color(0xFF4F7CFF)

/** Selection frame and alignment guides in the preview (yellow, like Instagram Edits). */
val GuideYellow = Color(0xFFFFD60A)

/**
 * Calls [onEnd] when every finger has left the element after a touch (also after a drag or a
 * pinch). Watches only - it consumes nothing, so the other gesture handlers work as before.
 */
fun androidx.compose.ui.Modifier.onGestureEnd(key: Any?, onEnd: () -> Unit): androidx.compose.ui.Modifier =
    this.then(
        androidx.compose.ui.Modifier.pointerInput(key) {
            androidx.compose.foundation.gestures.awaitEachGesture {
                androidx.compose.foundation.gestures.awaitFirstDown(requireUnconsumed = false)
                while (true) {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                    if (event.changes.none { it.pressed }) break
                }
                onEnd()
            }
        },
    )
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

/**
 * Video frame / image thumbnail. Shows a cached frame at once if there is one (the exact one or,
 * while it loads, the nearest known frame of the same file) and swaps in the exact frame when it
 * is ready. Loading continues even if this tile leaves the screen, so it is never wasted.
 */
@Composable
fun Thumb(
    uri: String,
    kind: MediaKind,
    timeMs: Long,
    modifier: Modifier = Modifier,
    maxSize: Int = 160,
    contentScale: ContentScale = ContentScale.Crop,
    background: Color = Color(0xFF22252C),
    exact: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    var bitmap by remember(uri, timeMs, maxSize, exact) {
        mutableStateOf(
            Thumbnails.peek(uri, kind, timeMs, maxSize, exact)
                ?: if (kind == MediaKind.VIDEO) Thumbnails.nearest(uri, timeMs, maxSize) else null,
        )
    }
    LaunchedEffect(uri, timeMs, maxSize, exact) {
        if (Thumbnails.peek(uri, kind, timeMs, maxSize, exact) == null) {
            Thumbnails.load(context, uri, kind, timeMs, maxSize, exact)?.let { bitmap = it }
        } else {
            bitmap = Thumbnails.peek(uri, kind, timeMs, maxSize, exact)
        }
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

/**
 * Media Import entry point. Returns a function that opens an "Import from" chooser:
 *  - Gallery: the phone's photo/video gallery (Android photo picker)
 *  - Files & Google Drive: phone storage, Downloads, Google Drive and other file apps
 * [videoOnly] limits the choice to videos; [multiple] allows picking several files.
 */
@Composable
fun rememberMediaImporter(videoOnly: Boolean, multiple: Boolean, onPicked: (List<Uri>) -> Unit): () -> Unit {
    var show by remember { mutableStateOf(false) }
    val deliver by rememberUpdatedState(onPicked)
    val galleryMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) deliver(uris)
    }
    val galleryOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) deliver(listOf(uri))
    }
    val filesMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) deliver(uris)
    }
    val filesOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) deliver(listOf(uri))
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("Import from") },
            text = {
                Column {
                    ImportSourceRow(Icons.Filled.PhotoLibrary, "Gallery", "Photos and videos on this phone") {
                        show = false
                        val type = if (videoOnly) ActivityResultContracts.PickVisualMedia.VideoOnly
                        else ActivityResultContracts.PickVisualMedia.ImageAndVideo
                        val request = PickVisualMediaRequest.Builder().setMediaType(type).build()
                        if (multiple) galleryMany.launch(request) else galleryOne.launch(request)
                    }
                    Spacer(Modifier.padding(top = 8.dp))
                    ImportSourceRow(Icons.Filled.Folder, "Files & Google Drive", "Phone storage, Downloads, Google Drive and other apps") {
                        show = false
                        val types = if (videoOnly) arrayOf("video/*") else arrayOf("video/*", "image/*")
                        if (multiple) filesMany.launch(types) else filesOne.launch(types)
                    }
                }
            },
            confirmButton = { },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        )
    }
    return { show = true }
}

@Composable
private fun ImportSourceRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = Color.Gray)
        }
    }
}
