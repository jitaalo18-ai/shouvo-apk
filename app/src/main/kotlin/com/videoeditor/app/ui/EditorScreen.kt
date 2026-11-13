package com.videoeditor.app.ui

import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.videoeditor.core.FrameState
import com.videoeditor.core.Framing
import com.videoeditor.core.MediaKind
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlinx.coroutines.delay

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
    val importMain = rememberMediaImporter(videoOnly = false, multiple = true) { uris ->
        ctl.appendMedia(uris)
    }
    val importVideoFx = rememberMediaImporter(videoOnly = true, multiple = true) { uris ->
        ctl.importToLibrary(LibraryKind.VIDEO_FX, uris)
    }

    var showStretch by remember { mutableStateOf(false) }
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
                Text(
                    "W\u00D7H",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (showStretch) Accent else Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Panel)
                        .clickable { showStretch = !showStretch }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                IconButton(onClick = { ctl.undo() }, enabled = ctl.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = { ctl.redo() }, enabled = ctl.canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                }
            }

            // W x H panel: make the selected clip (or the one under the playhead) wider / taller
            if (showStretch) {
                val target = ctl.timeline.selected?.takeIf { it.track == Track.MAIN || it.track == Track.OVERLAY }
                    ?: TimelineOps.locate(ctl.timeline, position)?.clip
                if (target != null) {
                    Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 4.dp)) {
                        StretchSlider("Width", target.stretchX) { ctl.stretch(target.id, it, target.stretchY) }
                        StretchSlider("Height", target.stretchY) { ctl.stretch(target.id, target.stretchX, it) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (target.track == Track.OVERLAY) "Overlay" else "Main video",
                                fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { ctl.stretch(target.id, 1f, 1f) }) { Text("Reset") }
                            TextButton(onClick = { showStretch = false }) { Text("Done") }
                        }
                    }
                }
            }

            // timeline
            TimelineView(ctl, position, Modifier.fillMaxWidth().height(timelineHeight(ctl.timeline)), onAddMedia = { importMain() })

            // toolbars
            val selected = ctl.timeline.selected
            if (selected == null) {
                Row(
                    Modifier.fillMaxWidth().background(Bg).horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Visible buttons only; their functions are specified later.
                    ToolButton(Icons.Filled.GraphicEq, "Sound FX") { ctl.library = LibraryKind.SOUND_FX }
                    ToolButton(Icons.Filled.MusicNote, "BG MUSIC") { ctl.library = LibraryKind.MUSIC }
                    ToolButton(Icons.Filled.Movie, "Video FX") { ctl.library = LibraryKind.VIDEO_FX }
                    ToolButton(Icons.Filled.Layers, "Overlay") { importOverlay() }
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
                        ToolButton(Icons.Filled.MusicNote, "BG MUSIC") { ctl.library = LibraryKind.MUSIC }
                        if (selected.track == Track.MAIN) {
                            // working tools
                            ToolButton(Icons.Filled.ContentCut, "Split") { ctl.split() }
                            ToolButton(Icons.Filled.AutoFixHigh, "AutoCut") { ctl.autoCut() }
                            ToolButton(Icons.Filled.Movie, "Video FX") { ctl.library = LibraryKind.VIDEO_FX }
                            ToolButton(Icons.Filled.GraphicEq, "Sound FX") { ctl.library = LibraryKind.SOUND_FX }
                            ToolButton(Icons.Filled.Layers, "Overlay") { importOverlay() }
                            ToolButton(Icons.Filled.Delete, "Delete") { ctl.deleteSelected() }
                            ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Move left") { ctl.moveSelectedMain(-1) }
                            ToolButton(Icons.AutoMirrored.Filled.ArrowForward, "Move right") { ctl.moveSelectedMain(1) }
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
        LaunchedEffect(kind) { ctl.player.pause() }
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
private fun StretchSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(52.dp))
        Slider(
            value = value.coerceIn(0.5f, 2f),
            onValueChange = onChange,
            valueRange = 0.5f..2f,
            modifier = Modifier.weight(1f),
        )
        Text("${(value * 100).toInt()}%", fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
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
 * the source files). The black box has the aspect ratio of the rendered output, so positions in
 * the preview and in the exported video match.
 *
 * Framing gestures on the main video: two fingers move, zoom and rotate it; one finger moves it
 * while the clip is selected; a tap selects / deselects it.
 */
@Composable
private fun PreviewCanvas(ctl: EditorController, position: Long) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val aspect = ctl.canvasAspect
        val canvasW: Dp = if (aspect > maxWidth / maxHeight) maxWidth else maxHeight * aspect
        val canvasH: Dp = canvasW / aspect
        val density = LocalDensity.current
        val canvasWPx = with(density) { canvasW.toPx() }
        val canvasHPx = with(density) { canvasH.toPx() }

        val current = TimelineOps.locate(ctl.timeline, position)?.clip
        val latest by rememberUpdatedState(current)

        // running state of the current framing gesture (unsnapped values) and the active guides
        val haptics = LocalHapticFeedback.current
        val gesture = remember { FramingGesture() }
        var guides by remember { mutableStateOf(0) }

        // small read-out (position, angle, zoom) shown while the video is being framed
        var readoutTick by remember { mutableStateOf(0L) }
        var showReadout by remember { mutableStateOf(false) }
        LaunchedEffect(readoutTick) {
            if (readoutTick != 0L) {
                showReadout = true
                delay(900)
                showReadout = false
            }
        }

        // Where the main clip sits: fitted into the frame, then zoomed / moved / rotated.
        val place: Modifier = if (current != null) {
            val clipAspect = current.aspect.coerceAtLeast(0.01f)
            val fitW: Dp = if (clipAspect > aspect) canvasW else canvasH * clipAspect
            val zoomed = fitW * TimelineOps.mainZoom(current)
            val w = zoomed * current.stretchX
            val h = zoomed / clipAspect * current.stretchY
            Modifier
                .offset(x = canvasW * current.posX - w / 2, y = canvasH * current.posY - h / 2)
                .size(w, h)
                .rotate(current.rotation)
        } else {
            Modifier
        }

        // Not clipped itself, so the selection border may reach outside the frame.
        Box(Modifier.size(canvasW, canvasH)) {
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                if (current != null) {
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
                }

                // Touch layer for the main video. It lies above the video surface so the gestures
                // always arrive here, and below the overlays so they keep their own gestures.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { detectTapGestures { ctl.select(latest?.id) } }
                        .pointerInput(canvasWPx, canvasHPx, aspect) {
                            detectTransformGestures { _, pan, zoom, rotation ->
                                val c = latest ?: return@detectTransformGestures
                                val twoFingers = zoom != 1f || rotation != 0f
                                if (twoFingers || c.selected) {
                                    val now = System.nanoTime()
                                    // a new gesture starts from where the clip is now
                                    if (gesture.clipId != c.id || now - gesture.lastEvent > 400_000_000L) {
                                        gesture.clipId = c.id
                                        gesture.x = c.posX
                                        gesture.y = c.posY
                                        gesture.zoom = TimelineOps.mainZoom(c)
                                        gesture.rotation = c.rotation
                                        gesture.snapKey = 0
                                    }
                                    gesture.lastEvent = now
                                    gesture.x = (gesture.x + pan.x / canvasWPx).coerceIn(0f, 1f)
                                    gesture.y = (gesture.y + pan.y / canvasHPx).coerceIn(0f, 1f)
                                    gesture.zoom = (gesture.zoom * zoom)
                                        .coerceIn(TimelineOps.MIN_MAIN_ZOOM, TimelineOps.MAX_MAIN_ZOOM)
                                    gesture.rotation += rotation
                                    // lock onto centre / edges / straight angles when close to them
                                    val snapped = Framing.snap(
                                        FrameState(gesture.x, gesture.y, gesture.zoom, gesture.rotation),
                                        c.aspect, aspect, c.stretchX, c.stretchY,
                                    )
                                    if (snapped.key and gesture.snapKey.inv() != 0) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    gesture.snapKey = snapped.key
                                    guides = snapped.key
                                    ctl.transformMain(
                                        c.id, snapped.state.x, snapped.state.y, snapped.state.zoom, snapped.state.rotation,
                                    )
                                    readoutTick = now
                                }
                            }
                        },
                )

                val total = ctl.timeline.durationMs
                for (clip in ctl.timeline.track(Track.OVERLAY).sortedBy { it.layer }) {
                    key(clip.id) {
                        if (position >= clip.timelineStartMs && position < clip.timelineEndMs && position < total) {
                            OverlayView(ctl, clip, canvasW, canvasH, canvasWPx, canvasHPx)
                        }
                    }
                }
            }

            if (current != null && current.selected) Box(place.border(2.dp, Accent))

            // alignment guides: dashed lines at the frame centre / edges the video is locked onto
            if (showReadout && guides != 0) {
                Canvas(Modifier.matchParentSize()) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    val stroke = 2.dp.toPx()
                    fun vertical(x: Float) = drawLine(Accent, Offset(x, 0f), Offset(x, size.height), stroke, pathEffect = dash)
                    fun horizontal(y: Float) = drawLine(Accent, Offset(0f, y), Offset(size.width, y), stroke, pathEffect = dash)
                    if (guides and 1 != 0) vertical(size.width / 2f)
                    if (guides and 2 != 0) horizontal(size.height / 2f)
                    if (guides and 4 != 0) vertical(0f)
                    if (guides and 8 != 0) vertical(size.width)
                    if (guides and 16 != 0) horizontal(0f)
                    if (guides and 32 != 0) horizontal(size.height)
                }
            }

            if (showReadout && current != null) {
                val (outW, outH) = ctl.canvasPixels
                val x = ((current.posX - 0.5f) * outW).toInt()
                val y = ((current.posY - 0.5f) * outH).toInt()
                val text = String.format(
                    java.util.Locale.US, "X%d Y%d %d\u00B0 %.2f\u00D7",
                    x, y, current.rotation.toInt(), TimelineOps.mainZoom(current),
                )
                Text(
                    text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .background(Color(0xCC1B1D23), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun OverlayView(ctl: EditorController, clip: Clip, canvasW: Dp, canvasH: Dp, canvasWPx: Float, canvasHPx: Float) {
    val current by rememberUpdatedState(clip)
    val w = canvasW * clip.scale * clip.stretchX
    val h = canvasW * clip.scale / clip.aspect.coerceAtLeast(0.01f) * clip.stretchY
    // Gestures are read before the rotation is applied, so drags follow the finger on screen.
    var modifier = Modifier
        .offset(x = canvasW * clip.posX - w / 2, y = canvasH * clip.posY - h / 2)
        .size(w, h)
        .pointerInput(clip.id) { detectTapGestures { ctl.select(current.id) } }
    if (clip.selected) {
        modifier = modifier.pointerInput(clip.id, canvasWPx, canvasHPx) {
            // one finger drags; two fingers resize and rotate
            detectTransformGestures { _, pan, zoom, rotation ->
                val c = current
                ctl.transform(
                    c.id,
                    c.posX + pan.x / canvasWPx,
                    c.posY + pan.y / canvasHPx,
                    c.scale * zoom,
                    c.rotation + rotation,
                )
            }
        }
    }
    modifier = modifier.rotate(clip.rotation)
    if (clip.selected) modifier = modifier.border(2.dp, Accent)
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

/** Unsnapped values of the framing gesture in progress. */
private class FramingGesture {
    var clipId: String? = null
    var lastEvent = 0L
    var x = 0.5f
    var y = 0.5f
    var zoom = 1f
    var rotation = 0f
    var snapKey = 0
}
