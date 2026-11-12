package com.videoeditor.app.ui

import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
                Spacer(Modifier.height(10.dp))
                if (items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Nothing saved yet.", color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(items, key = { it.id }) { item ->
                            LibraryRow(
                                app = app,
                                kind = kind,
                                item = item,
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
            val uri = remember(item.id) { Uri.fromFile(app.library.file(item)).toString() }
            Thumb(uri, MediaKind.VIDEO, 0, thumb)
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
