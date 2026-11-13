package com.videoeditor.app.ui

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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.videoeditor.app.App
import com.videoeditor.app.data.LibraryKind
import com.videoeditor.app.data.ProjectEntity
import com.videoeditor.app.media.ExtractOutcome
import com.videoeditor.app.media.MediaProbe
import com.videoeditor.app.media.MusicExtractor
import com.videoeditor.core.Clip
import com.videoeditor.core.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Step 1: Projects home screen. */
@Composable
fun ProjectsScreen(app: App, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by remember { app.projects.observeProjects() }.collectAsState(initial = emptyList())
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val importMedia = rememberMediaImporter(videoOnly = false, multiple = true) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importing = true
                error = null
                val media = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        persistRead(context, uri)
                        MediaProbe.probe(context, uri)
                    }.filter { it.kind != MediaKind.AUDIO }
                }
                if (media.isEmpty()) {
                    error = "None of the selected files could be opened as a video or image."
                    importing = false
                } else {
                    val id = app.projects.create(media)
                    importing = false
                    onOpen(id)
                }
            }
        }
    }

    // "Extract Music from Video": pick a library, pick a video, remove the voice, save the music.
    var chooseTarget by remember { mutableStateOf(false) }
    var extractTarget by remember { mutableStateOf(LibraryKind.MUSIC) }
    var extractProgress by remember { mutableStateOf<Float?>(null) }
    var extractResult by remember { mutableStateOf<String?>(null) }
    val musicPicker = rememberMediaImporter(videoOnly = true, multiple = false) { uris ->
        val uri = uris.firstOrNull()
        if (uri != null && extractProgress == null) {
            val target = extractTarget
            scope.launch {
                extractProgress = 0f
                try {
                    val file = app.library.newFile(target, "wav")
                    val outcome = MusicExtractor.extract(context.applicationContext, uri, file) { extractProgress = it }
                    extractResult = when (outcome) {
                        is ExtractOutcome.Failed -> outcome.message
                        ExtractOutcome.Saved -> {
                            val base = withContext(Dispatchers.IO) { MediaProbe.displayName(context, uri) }
                                .substringBeforeLast('.').ifEmpty { "Video" }
                            val name = "$base (music)"
                            if (app.library.addFile(target, name, file, "audio/wav")) {
                                "Saved to ${target.title} as \"$name\". Open a project and tap ${target.title} to use it."
                            } else {
                                "The extracted music could not be saved."
                            }
                        }
                    }
                } finally {
                    extractProgress = null
                }
            }
        }
    }

    var confirmDelete by remember { mutableStateOf<ProjectEntity?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Projects", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { importMedia() },
            enabled = !importing,
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (importing) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (importing) "Importing…" else "New Project", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { chooseTarget = true },
            enabled = extractProgress == null,
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Extract Music from Video", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No projects yet.\nTap New Project to pick videos and images.", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(projects, key = { it.id }) { project ->
                    ProjectRow(app, project, onDelete = { confirmDelete = project }) { onOpen(project.id) }
                }
            }
        }
    }

    if (chooseTarget) {
        AlertDialog(
            onDismissRequest = { chooseTarget = false },
            title = { Text("Extract Music from Video") },
            text = {
                Text(
                    "Pick a video. The voice is removed and only the background music is saved inside the app.\n\n" +
                        "This works when the video has stereo sound with the voice in the middle. " +
                        "It cannot separate voice and music in mono recordings.\n\nWhere should the music be saved?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    chooseTarget = false
                    extractTarget = LibraryKind.MUSIC
                    musicPicker()
                }) { Text("Background Music") }
            },
            dismissButton = {
                TextButton(onClick = {
                    chooseTarget = false
                    extractTarget = LibraryKind.SOUND_FX
                    musicPicker()
                }) { Text("Sound FX") }
            },
        )
    }

    extractProgress?.let { p ->
        Dialog(onDismissRequest = { }, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(shape = RoundedCornerShape(16.dp), color = Panel) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Removing the voice and saving the music…", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(p * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
    }

    confirmDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete project?") },
            text = {
                Text(
                    "\"${project.name}\" and all its edits will be deleted. This cannot be undone.\n\n" +
                        "Your videos and photos in the Gallery are not deleted.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        scope.launch {
                            app.projects.delete(project.id)
                            // library files that were only kept for this project can go now
                            app.library.cleanUpRemoved()
                            context.getSharedPreferences("project_settings", android.content.Context.MODE_PRIVATE)
                                .edit().remove("format_${project.id}").apply()
                        }
                    },
                    modifier = Modifier.testTag("confirmDelete"),
                ) { Text("Delete", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    extractResult?.let { text ->
        AlertDialog(
            onDismissRequest = { extractResult = null },
            title = { Text("Extract Music from Video") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { extractResult = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun ProjectRow(app: App, project: ProjectEntity, onDelete: () -> Unit, onClick: () -> Unit) {
    val first by produceState<Clip?>(initialValue = null, project.id, project.updatedAt) {
        value = app.projects.firstMainClip(project.id)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val clip = first
        val thumbModifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
        if (clip != null) {
            Thumb(clip.sourceUri, clip.kind, clip.sourceStartMs, thumbModifier)
        } else {
            Box(thumbModifier.background(Color(0xFF22252C)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(project.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val edited = remember(project.updatedAt) {
                SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(project.updatedAt))
            }
            Text("Edited $edited", color = Color.Gray, fontSize = 12.sp)
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("projectMenu_${project.id}")) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Project options")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Delete project") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("projectDelete"),
                )
            }
        }
    }
}
