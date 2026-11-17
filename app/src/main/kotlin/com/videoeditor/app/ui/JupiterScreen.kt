package com.videoeditor.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.videoeditor.app.App
import com.videoeditor.app.media.MediaProbe
import com.videoeditor.app.media.SoundRemover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tests: the videos the "Choose videos" button gives instead of opening the phone's picker. */
object JupiterTest {
    @Volatile
    var picks: List<Uri>? = null
}

/** One video in Jupiter and what happened to it. */
private class JupiterItem(val uri: Uri, val name: String, val durationMs: Long, val hasSound: Boolean) {
    var state by mutableStateOf<State>(State.Waiting)

    sealed class State {
        object Waiting : State()
        data class Working(val progress: Float) : State()
        data class Done(val saved: SoundRemover.Saved) : State()
        data class Error(val message: String) : State()
    }
}

/** The planet: a banded orange circle (Jupiter's look), drawn, not an image. */
@Composable
fun JupiterIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
        clipPath(disc) {
            drawRect(Color(0xFFE8C9A0))
            val bands = listOf(
                0.18f to Color(0xFFC98B5A), 0.33f to Color(0xFFF2DEC2), 0.45f to Color(0xFFB8693F),
                0.6f to Color(0xFFEBD3B0), 0.72f to Color(0xFFC07A4E), 0.86f to Color(0xFFDDB894),
            )
            for ((y, color) in bands) {
                drawRect(color, topLeft = Offset(c.x - r, c.y - r + 2 * r * y), size = Size(2 * r, 2 * r * 0.09f))
            }
            // the Great Red Spot
            drawOval(Color(0xFFA9472E), topLeft = Offset(c.x + r * 0.12f, c.y + r * 0.2f), size = Size(r * 0.5f, r * 0.26f))
        }
    }
}

/**
 * Jupiter (like the BlackHole app): choose one or more videos, tap "Remove sound", and silent
 * copies are saved to the Gallery (Movies/Jupiter). The originals are not changed.
 */
@Composable
fun JupiterScreen(app: App, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<JupiterItem>() }
    var job by remember { mutableStateOf<Job?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val working = job != null

    fun add(uris: List<Uri>) {
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                uris.filter { u -> items.none { it.uri == u } }.distinct().mapNotNull { u ->
                    persistRead(context, u)
                    val info = MediaProbe.probe(context, u) ?: return@mapNotNull null
                    if (info.kind != com.videoeditor.core.MediaKind.VIDEO) return@mapNotNull null
                    JupiterItem(u, info.name, info.durationMs, SoundRemover.hasSound(context, u))
                }
            }
            if (found.isEmpty() && uris.isNotEmpty()) note = "Those files could not be opened as videos."
            items.addAll(found)
        }
    }

    val picker = rememberMediaImporter(videoOnly = true, multiple = true) { add(it) }
    fun choose() {
        val test = JupiterTest.picks
        if (test != null) add(test) else picker()
    }

    fun start() {
        val todo = items.filter { it.state !is JupiterItem.State.Done }
        if (todo.isEmpty()) return
        note = null
        job = scope.launch {
            var saved = 0
            try {
                for (item in todo) {
                    if (!item.hasSound) {
                        item.state = JupiterItem.State.Error("Already has no sound")
                        continue
                    }
                    item.state = JupiterItem.State.Working(0f)
                    item.state = try {
                        val s = SoundRemover.removeSound(context, item.uri) { p -> item.state = JupiterItem.State.Working(p) }
                        saved++
                        JupiterItem.State.Done(s)
                    } catch (e: SoundRemover.Failed) {
                        JupiterItem.State.Error(e.message ?: "Failed")
                    }
                }
                note = when (saved) {
                    0 -> "Nothing was saved."
                    1 -> "Saved to Gallery › Movies/${SoundRemover.FOLDER}"
                    else -> "$saved videos saved to Gallery › Movies/${SoundRemover.FOLDER}"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                for (it in todo) if (it.state is JupiterItem.State.Working) it.state = JupiterItem.State.Waiting
                note = "Stopped. ${if (saved > 0) "$saved saved before stopping." else "Nothing was saved."}"
            } finally {
                job = null
            }
        }
    }

    BackHandler { if (working) job?.cancel() else onClose() }

    Dialog(onDismissRequest = { if (!working) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("jupiterScreen"), color = Color(0xFF0E1015), contentColor = Color.White) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    JupiterIcon(Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Jupiter", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("Removes the sound from videos", fontSize = 13.sp, color = Color.Gray)
                    }
                    IconButton(onClick = { if (working) job?.cancel() else onClose() }, modifier = Modifier.testTag("jupiterClose")) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { choose() },
                    enabled = !working,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("jupiterChoose"),
                ) {
                    Icon(Icons.Filled.VideoLibrary, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (items.isEmpty()) "Choose videos" else "Add more videos", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
                if (items.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            JupiterIcon(Modifier.size(110.dp))
                            Spacer(Modifier.height(18.dp))
                            Text(
                                "Choose videos, then tap Remove sound.\nSilent copies go to your Gallery. The originals stay as they are.",
                                color = Color.Gray, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(items, key = { it.uri.toString() }) { item ->
                            JupiterRow(context, item, removable = !working) { items.remove(item) }
                        }
                    }
                }
                note?.let {
                    Text(it, fontSize = 13.sp, color = Color(0xFFB9C7FF), modifier = Modifier.padding(vertical = 8.dp).testTag("jupiterNote"))
                }
                val todo = items.count { it.state !is JupiterItem.State.Done }
                Button(
                    onClick = { if (working) job?.cancel() else start() },
                    enabled = working || todo > 0,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (working) Color(0xFF3A3D46) else Color(0xFFD9824B)),
                    modifier = Modifier.fillMaxWidth().testTag("jupiterRemove"),
                ) {
                    if (working) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Stop", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    } else {
                        Icon(Icons.Filled.VolumeOff, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(if (todo > 1) "Remove sound ($todo)" else "Remove sound", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun JupiterRow(context: Context, item: JupiterItem, removable: Boolean, onRemove: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, item.uri)
                r.getScaledFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)
            } catch (e: Exception) {
                null
            } finally {
                r.release()
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1F26)).padding(10.dp).testTag("jupiterItem"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2D35)), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val time = "%d:%02d".format(item.durationMs / 60_000, (item.durationMs / 1000) % 60)
            when (val s = item.state) {
                JupiterItem.State.Waiting ->
                    Text(if (item.hasSound) "$time · has sound" else "$time · no sound", fontSize = 12.sp, color = Color.Gray)
                is JupiterItem.State.Working -> {
                    Text("Removing the sound… ${(s.progress * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                }
                is JupiterItem.State.Done ->
                    Text("Saved: ${s.saved.name}", fontSize = 12.sp, color = Color(0xFF8BE39B), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("jupiterSaved"))
                is JupiterItem.State.Error -> Text(s.message, fontSize = 12.sp, color = Color(0xFFFF8A80), modifier = Modifier.testTag("jupiterError"))
            }
        }
        val done = item.state as? JupiterItem.State.Done
        if (done != null) {
            IconButton(onClick = { open(context, done.saved.uri, Intent.ACTION_VIEW) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play") }
            IconButton(onClick = { open(context, done.saved.uri, Intent.ACTION_SEND) }) { Icon(Icons.Filled.Share, contentDescription = "Share") }
        } else if (removable) {
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove from list", tint = Color.Gray) }
        }
    }
}

private fun open(context: Context, uri: Uri, action: String) {
    val intent = if (action == Intent.ACTION_SEND) {
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share video")
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

/** The Jupiter button of the Projects screen. */
@Composable
fun JupiterButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color(0xFF1C1F26),
        modifier = modifier.testTag("jupiterButton"),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            JupiterIcon(Modifier.size(34.dp))
            Spacer(Modifier.width(10.dp))
            Text("Jupiter", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
