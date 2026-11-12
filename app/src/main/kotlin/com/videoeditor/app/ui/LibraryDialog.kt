package com.videoeditor.app.ui

import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.videoeditor.app.media.Thumbnails
import com.videoeditor.app.App
import com.videoeditor.app.data.LibraryItemEntity
import com.videoeditor.app.data.LibraryKind
import com.videoeditor.app.data.RemoveCheck
import com.videoeditor.core.MediaKind
import kotlinx.coroutines.launch

/**
 * Steps 5-7: the Video FX, Sound FX and Background Music libraries. One screen, three separate
 * libraries (separate records and separate storage folders). Items are shared by all projects.
 */
@Composable
fun LibraryDialog(
    app: App,
    kind: LibraryKind,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onInsert: (LibraryItemEntity) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val items by remember(kind) { app.library.observe(kind) }.collectAsState(initial = emptyList())
    var renaming by remember { mutableStateOf<LibraryItemEntity?>(null) }
    var removing by remember { mutableStateOf<RemoveCheck?>(null) }
    val searchable = kind == LibraryKind.VIDEO_FX || kind == LibraryKind.SOUND_FX

    // Preview: one small player; the clip that is playing is shown inside its own thumbnail box.
    val context = LocalContext.current
    var playingId by remember { mutableStateOf<Long?>(null) }
    val previewPlayer = remember { ExoPlayer.Builder(context.applicationContext).build() }
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) playingId = null
            }
        }
        previewPlayer.addListener(listener)
        onDispose { previewPlayer.release() }
    }
    val togglePreview: (LibraryItemEntity) -> Unit = { item ->
        if (playingId == item.id) {
            previewPlayer.stop()
            playingId = null
        } else {
            previewPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(app.library.file(item))))
            previewPlayer.prepare()
            previewPlayer.play()
            playingId = item.id
        }
    }
    var query by remember(kind) { mutableStateOf("") }
    val shown = if (query.isBlank()) items else items.filter { it.name.contains(query.trim(), ignoreCase = true) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(kind.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAdd, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(kind.addLabel)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Saved inside the app. Tap an item to insert it at the playhead.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
                if (searchable && items.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search by name") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (items.isEmpty()) "Nothing saved yet." else "No item matches \"${query.trim()}\".", color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(shown, key = { it.id }) { item ->
                            LibraryRow(
                                app = app,
                                kind = kind,
                                item = item,
                                playing = playingId == item.id,
                                player = previewPlayer,
                                onTogglePreview = { togglePreview(item) },
                                onInsert = { onInsert(item) },
                                onRename = { renaming = item },
                                onRemove = { scope.launch { removing = app.library.checkRemove(item.id) } },
                            )
                        }
                    }
                }
            }
        }

        renaming?.let { item ->
            var text by remember(item.id) { mutableStateOf(item.name) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text("Rename") },
                text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
                confirmButton = {
                    TextButton(
                        enabled = text.isNotBlank(),
                        onClick = {
                            scope.launch { app.library.rename(item.id, text) }
                            renaming = null
                        },
                    ) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            )
        }

        removing?.let { check ->
            val used = check.usedInProjects
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text("Remove from ${kind.title}?") },
                text = {
                    Text(
                        if (used.isEmpty()) {
                            "\"${check.item.name}\" will be deleted from the app. The original file on your phone is not touched."
                        } else {
                            "\"${check.item.name}\" is used in ${used.size} project(s): ${used.joinToString()}.\n\n" +
                                "It will be removed from the library, but those projects keep working: " +
                                "the saved file is only deleted once no project uses it any more."
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch { app.library.remove(check.item.id) }
                            removing = null
                        },
                    ) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun LibraryRow(
    app: App,
    kind: LibraryKind,
    item: LibraryItemEntity,
    playing: Boolean,
    player: ExoPlayer,
    onTogglePreview: () -> Unit,
    onInsert: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .clickable(onClick = onInsert)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
        if (kind == LibraryKind.VIDEO_FX) {
            val context = LocalContext.current.applicationContext
            val uri = remember(item.id) { Uri.fromFile(app.library.file(item)).toString() }
            val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
                value = Thumbnails.representative(context, uri, item.durationMs)
            }
            // Tap the box to play / stop the clip inside it.
            Box(thumb.background(Color(0xFF22252C)).clickable(onClick = onTogglePreview), contentAlignment = Alignment.Center) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (playing) {
                    // cover the square box without distorting the video
                    val aspect = item.aspect.coerceIn(0.2f, 5f)
                    val w = if (aspect >= 1f) 56.dp * aspect else 56.dp
                    val h = if (aspect >= 1f) 56.dp else 56.dp / aspect
                    AndroidView(
                        factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                        onRelease = {
                            try {
                                player.clearVideoTextureView(it)
                            } catch (e: Exception) {
                                // player already released
                            }
                        },
                        modifier = Modifier.requiredSize(w, h),
                    )
                }
                Icon(
                    if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Stop preview" else "Play preview",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp).background(Color(0x80000000), CircleShape).padding(2.dp),
                )
            }
        } else {
            Box(thumb.background(Color(0xFF22252C)), contentAlignment = Alignment.Center) {
                Icon(
                    if (kind == LibraryKind.MUSIC) Icons.Filled.MusicNote else Icons.Filled.GraphicEq,
                    contentDescription = null,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(formatTime(item.durationMs), fontSize = 12.sp, color = Color.Gray)
        }
        IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, contentDescription = "Rename") }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
    }
}
