package com.videoeditor.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoeditor.app.App
import com.videoeditor.app.data.ProjectEntity
import com.videoeditor.app.media.MediaProbe
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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
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

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Projects", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { picker.launch(arrayOf("video/*", "image/*")) },
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
        Spacer(Modifier.height(16.dp))
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No projects yet.\nTap New Project to pick videos and images.", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(projects, key = { it.id }) { project ->
                    ProjectRow(app, project) { onOpen(project.id) }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(app: App, project: ProjectEntity, onClick: () -> Unit) {
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
    }
}
