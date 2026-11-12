package com.videoeditor.app.ui

import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.videoeditor.app.App
import com.videoeditor.app.data.LibraryKind
import com.videoeditor.app.media.CanvasFormat
import com.videoeditor.core.Clip
import com.videoeditor.core.MediaKind
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track

/** Step 2: the editing screen - preview, play/pause, timeline and toolbars. */
@Composable
fun EditorScreen(app: App, projectId: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctl = remember { EditorController(app, projectId, scope) }
    DisposableEffect(Unit) { onDispose { ctl.release() } }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                ctl.player.pause()
                ctl.save()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val position by ctl.player.positionMs.collectAsState()
    val playing by ctl.player.isPlaying.collectAsState()

    val libraryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val kind = ctl.library
        if (kind != null) ctl.importToLibrary(kind, uris)
    }
    val importOverlay = rememberMediaImporter(videoOnly = false, multiple = false) { uris ->
        uris.firstOrNull()?.let { ctl.addOverlay(it) }
    }
    val importVideoFx = rememberMediaImporter(videoOnly = true, multiple = true) { uris ->
        ctl.importToLibrary(LibraryKind.VIDEO_FX, uris)
    }

    val snackbar = remember { SnackbarHostState() }
    val message = ctl.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            ctl.message = null
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            // top bar: close, project name, quality label, export
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = "Close project")
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ctl.projectName,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Color.Gray)
                }
                IconButton(onClick = { ctl.comingSoon("Search") }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
                Text("FHD", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { ctl.export() },
                    enabled = ctl.loaded && ctl.busy == null,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) {
                    Text("Export", fontWeight = FontWeight.SemiBold)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
            }

            // video preview
            Box(Modifier.weight(1f).fillMaxWidth().background(Panel).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                if (ctl.loaded) PreviewCanvas(ctl, position)
            }

            // transport row: play on the left, time in the middle, undo / redo on the right
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { ctl.player.toggle() }) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(34.dp),
                    )
                }
                FormatMenu(ctl)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatTime(position), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(formatTime(ctl.timeline.durationMs), fontSize = 12.sp, color = Color.Gray)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { ctl.undo() }, enabled = ctl.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = { ctl.redo() }, enabled = ctl.canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                }
            }

            // timeline
            TimelineView(ctl, position, Modifier.fillMaxWidth().height(214.dp))

            // toolbars
            val selected = ctl.timeline.selected
            if (selected == null) {
                Row(
                    Modifier.fillMaxWidth().background(Panel).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    // Visible buttons only; their functions are specified later.
                    ToolButton(Icons.Filled.GraphicEq, "Audio") { }
                    ToolButton(Icons.Filled.TextFields, "Text") { }
                    ToolButton(Icons.Filled.Mic, "Voice") { }
                    ToolButton(Icons.Filled.Link, "Links") { }
                }
            } else {
                Row(Modifier.fillMaxWidth().background(Panel).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { ctl.select(null) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back", modifier = Modifier.size(30.dp))
                    }
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        if (selected.track == Track.MAIN) {
                            // working tools
                            ToolButton(Icons.Filled.ContentCut, "Split") { ctl.split() }
                            ToolButton(Icons.Filled.AutoFixHigh, "AutoCut") { ctl.autoCut() }
                            ToolButton(Icons.Filled.Movie, "Video FX") { ctl.library = LibraryKind.VIDEO_FX }
                            ToolButton(Icons.Filled.GraphicEq, "Sound FX") { ctl.library = LibraryKind.SOUND_FX }
                            ToolButton(Icons.Filled.MusicNote, "Music") { ctl.library = LibraryKind.MUSIC }
                            ToolButton(Icons.Filled.Layers, "Overlay") { importOverlay() }
                            ToolButton(Icons.Filled.Delete, "Delete") { ctl.deleteSelected() }
                            // shown now, functions come in later versions
                            ToolButton(Icons.Filled.AutoAwesome, "Effects") { ctl.comingSoon("Effects") }
                            ToolButton(Icons.Filled.Tune, "Adjust") { ctl.comingSoon("Adjust") }
                            ToolButton(Icons.Filled.PhotoFilter, "Filters") { ctl.comingSoon("Filters") }
                            ToolButton(Icons.Filled.Crop, "Crop") { ctl.comingSoon("Crop") }
                            ToolButton(Icons.Filled.OpenWith, "Transform") { ctl.comingSoon("Transform") }
                            ToolButton(Icons.Filled.Flip, "Mirror") { ctl.comingSoon("Mirror") }
                            ToolButton(Icons.Filled.Person, "Cutout") { ctl.comingSoon("Cutout") }
                            ToolButton(Icons.Filled.Vignette, "Mask") { ctl.comingSoon("Mask") }
                            ToolButton(Icons.Filled.Opacity, "Opacity") { ctl.comingSoon("Opacity") }
                            ToolButton(Icons.Filled.SwapHoriz, "Replace") { ctl.comingSoon("Replace") }
                            ToolButton(Icons.Filled.ContentCopy, "Copy") { ctl.comingSoon("Copy") }
                            ToolButton(Icons.Filled.FileCopy, "Duplicate") { ctl.comingSoon("Duplicate") }
                            ToolButton(Icons.Filled.Wallpaper, "Green screen") { ctl.comingSoon("Green screen") }
                        } else if (selected.track == Track.OVERLAY) {
                            // overlay tools: working ones first
                            ToolButton(Icons.Filled.ContentCut, "Split") { ctl.split() }
                            ToolButton(Icons.Filled.FileCopy, "Duplicate") { ctl.duplicateSelected() }
                            ToolButton(Icons.Filled.Delete, "Delete") { ctl.deleteSelected() }
                            // shown now, functions come in later versions
                            ToolButton(Icons.Filled.Opacity, "Opacity") { ctl.comingSoon("Opacity") }
                            ToolButton(Icons.Filled.SwapHoriz, "Replace") { ctl.comingSoon("Replace") }
                            ToolButton(Icons.Filled.Vignette, "Mask") { ctl.comingSoon("Mask") }
                            ToolButton(Icons.Filled.PhotoFilter, "Filters") { ctl.comingSoon("Filters") }
                            ToolButton(Icons.Filled.Tune, "Adjust") { ctl.comingSoon("Adjust") }
                            ToolButton(Icons.Filled.Flip, "Mirror") { ctl.comingSoon("Mirror") }
                            ToolButton(Icons.Filled.Person, "Cutout") { ctl.comingSoon("Cutout") }
                        } else {
                            ToolButton(Icons.Filled.ContentCut, "Split") { ctl.split() }
                            ToolButton(Icons.Filled.FileCopy, "Duplicate") { ctl.duplicateSelected() }
                            ToolButton(Icons.Filled.Delete, "Delete") { ctl.deleteSelected() }
                        }
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)) { data ->
            Snackbar(snackbarData = data)
        }
    }

    ctl.library?.let { kind ->
        LibraryDialog(
            app = app,
            kind = kind,
            onDismiss = { ctl.library = null },
            onAdd = { if (kind == LibraryKind.VIDEO_FX) importVideoFx() else libraryPicker.launch(arrayOf(kind.mime)) },
            onInsert = { ctl.insertFromLibrary(kind, it) },
        )
    }

    ctl.busy?.let { busy ->
        Dialog(onDismissRequest = { }, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(shape = RoundedCornerShape(16.dp), color = Panel) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(busy.label, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    val p = busy.progress
                    if (p != null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("${(p * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

/** Drop-down to switch the video format: original shape, 16:9, 9:16 or 1:1. */
@Composable
private fun FormatMenu(ctl: EditorController) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Panel)
                .clickable { open = true }
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ctl.format.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change video format")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in CanvasFormat.values()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            fontWeight = if (option == ctl.format) FontWeight.Bold else FontWeight.Normal,
                            color = if (option == ctl.format) Accent else Color.White,
                        )
                    },
                    onClick = {
                        open = false
                        ctl.chooseFormat(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 1, color = Color.LightGray)
    }
}

/**
 * The preview: main video at the bottom, overlays composited above it (nothing is merged into
 * the source files). The box has the aspect ratio of the rendered output, so overlay positions
 * in the preview and in the exported video match.
 */
@Composable
private fun PreviewCanvas(ctl: EditorController, position: Long) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val aspect = ctl.canvasAspect
        val canvasW: Dp = if (aspect > maxWidth / maxHeight) maxWidth else maxHeight * aspect
        val canvasH: Dp = canvasW / aspect
        val density = LocalDensity.current
        val canvasWPx = with(density) { canvasW.toPx() }
        val canvasHPx = with(density) { canvasH.toPx() }

        val current = TimelineOps.locate(ctl.timeline, position)?.clip
        val latest by rememberUpdatedState(current)
        Box(
            Modifier
                .size(canvasW, canvasH)
                .clipToBounds()
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { ctl.select(null) } }
                .pointerInput(canvasWPx, canvasHPx) {
                    // Two fingers: pinch to make the video bigger / smaller and move it.
                    // One finger moves it only while the clip is selected.
                    detectTransformGestures { _, pan, zoom, _ ->
                        val c = latest ?: return@detectTransformGestures
                        if (zoom != 1f || c.selected) {
                            ctl.transformMain(
                                c.id,
                                c.posX + pan.x / canvasWPx,
                                c.posY + pan.y / canvasHPx,
                                TimelineOps.mainZoom(c) * zoom,
                            )
                        }
                    }
                },
        ) {
            if (current != null) {
                // The video is fitted into the frame, then zoomed / moved as the user chose.
                val clipAspect = current.aspect.coerceAtLeast(0.01f)
                val fitW: Dp = if (clipAspect > aspect) canvasW else canvasH * clipAspect
                val zoom = TimelineOps.mainZoom(current)
                val w = fitW * zoom
                val h = w / clipAspect
                val place = Modifier
                    .offset(x = canvasW * current.posX - w / 2, y = canvasH * current.posY - h / 2)
                    .size(w, h)
                AndroidView(
                    factory = { context ->
                        TextureView(context).also { ctl.player.main.setVideoTextureView(it) }
                    },
                    onRelease = {
                        try {
                            ctl.player.main.clearVideoTextureView(it)
                        } catch (e: Exception) {
                            // player already released
                        }
                    },
                    modifier = place,
                )
                // Image clips on the main track are drawn here.
                if (current.kind == MediaKind.IMAGE) {
                    Thumb(
                        current.sourceUri, MediaKind.IMAGE, 0, place,
                        maxSize = 1280, contentScale = ContentScale.FillBounds, background = Color.Black,
                    )
                }
                if (current.selected) Box(place.border(2.dp, Accent))
            }

            val total = ctl.timeline.durationMs
            for (clip in ctl.timeline.track(Track.OVERLAY).sortedBy { it.layer }) {
                key(clip.id) {
                    if (position >= clip.timelineStartMs && position < clip.timelineEndMs && position < total) {
                        OverlayView(ctl, clip, canvasW, canvasH, canvasWPx, canvasHPx)
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayView(ctl: EditorController, clip: Clip, canvasW: Dp, canvasH: Dp, canvasWPx: Float, canvasHPx: Float) {
    val current by rememberUpdatedState(clip)
    val w = canvasW * clip.scale
    val h = w / clip.aspect.coerceAtLeast(0.01f)
    var modifier = Modifier
        .offset(x = canvasW * clip.posX - w / 2, y = canvasH * clip.posY - h / 2)
        .size(w, h)
        .pointerInput(clip.id) { detectTapGestures { ctl.select(current.id) } }
    if (clip.selected) {
        modifier = modifier
            .pointerInput(clip.id, canvasWPx, canvasHPx) {
                // one finger drags, two fingers resize
                detectTransformGestures { _, pan, zoom, _ ->
                    val c = current
                    ctl.transform(c.id, c.posX + pan.x / canvasWPx, c.posY + pan.y / canvasHPx, c.scale * zoom)
                }
            }
            .border(2.dp, Accent)
    }
    Box(modifier) {
        if (clip.kind == MediaKind.IMAGE) {
            Thumb(
                clip.sourceUri, MediaKind.IMAGE, 0, Modifier.fillMaxSize(),
                maxSize = 1080, contentScale = ContentScale.FillBounds, background = Color.Transparent,
            )
        } else {
            AndroidView(
                factory = { context ->
                    TextureView(context).also { ctl.player.bindOverlayView(clip.id, it) }
                },
                onRelease = { ctl.player.bindOverlayView(clip.id, null) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
