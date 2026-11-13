package com.videoeditor.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoeditor.app.media.Thumbnails
import com.videoeditor.app.media.Waveforms
import com.videoeditor.core.Clip
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.CheckCircle
import com.videoeditor.core.Compound
import com.videoeditor.core.MediaKind
import com.videoeditor.core.Timeline
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val RULER_HEIGHT = 26.dp
private val THUMB_HEIGHT = 56.dp
private val WAVE_HEIGHT = 20.dp
private val MAIN_HEIGHT = THUMB_HEIGHT + WAVE_HEIGHT
private val VISUAL_LANE = 44.dp      // overlay clips (thumbnails)
private val AUDIO_LANE = 30.dp       // sound effects and music (waveform)
private val LANE_GAP = 2.dp
private val HANDLE_WIDTH = 18.dp
private val WaveColor = Color(0xFFB42DA8)
private val SnapColor = Color(0xFFFFD60A)
private val ADD_AUDIO_HEIGHT = 20.dp
private val WaveBackground = Color(0xFF510D50)

private fun laneHeight(clips: List<Clip>, height: Dp): Dp {
    if (clips.isEmpty()) return 0.dp
    val count = (TimelineOps.lanes(clips).values.maxOrNull() ?: 0) + 1
    return height * count + LANE_GAP * count
}

/** Keep the preview large for simple projects; scroll vertically when many tracks overlap. */
fun timelineHeight(timeline: Timeline): Dp = (RULER_HEIGHT + 28.dp + MAIN_HEIGHT +
    (if (timeline.track(Track.MUSIC).isEmpty()) ADD_AUDIO_HEIGHT + LANE_GAP else 0.dp) +
    laneHeight(timeline.track(Track.OVERLAY), VISUAL_LANE) +
    laneHeight(timeline.track(Track.SFX), AUDIO_LANE) +
    laneHeight(timeline.track(Track.MUSIC), AUDIO_LANE) +
    laneHeight(timeline.track(Track.TEXT), AUDIO_LANE)).coerceIn(164.dp, 248.dp)

/** The part of the timeline content (in pixels from its start) that is on or near the screen. */
private class Visible(val left: Float, val right: Float)

private val CompoundColor = Color(0xFF6D4AFF)
private val CaptionColor = Color(0xFFF2C94C)

/**
 * Scrollable timeline with a fixed playhead in the middle (the content moves under it).
 * Dragging the timeline scrubs the video; during playback the timeline follows the player.
 * Layout from top to bottom: time ruler, music, sound effects, overlay track(s), main video
 * track with its audio waveform. [onAddMedia] is the "+" button at the right.
 */
@Composable
fun TimelineView(ctl: EditorController, position: Long, modifier: Modifier = Modifier, onAddMedia: () -> Unit) {
    val density = LocalDensity.current
    val timeline = ctl.timeline
    val contentEndMs = maxOf(timeline.durationMs, timeline.clips.maxOfOrNull { it.timelineEndMs } ?: 0L, 1L)
    // Store the view scale separately from edit decisions (no Undo entry or media changes).
    val baseScale = with(density) { 12.dp.toPx() } / 1000f
    val maxZoom = minOf(16f, 200_000f / contentEndMs / baseScale)
    val minZoom = minOf(0.25f, maxZoom)
    var zoom by rememberSaveable(ctl.projectId) { mutableStateOf(1f) }
    val pxPerMs = baseScale * zoom.coerceIn(minZoom, maxZoom)
    var pinching by remember { mutableStateOf(false) }
    var zoomAnchorMs by remember { mutableStateOf(0L) }
    val currentMinZoom by rememberUpdatedState(minZoom)
    val currentMaxZoom by rememberUpdatedState(maxZoom)

    // magnet: edges lock onto the playhead / other clips within 10 dp
    val snapMs = with(density) { 10.dp.toPx() / pxPerMs }.toLong().coerceAtLeast(1)
    val haptics = LocalHapticFeedback.current
    val guide = ctl.snapGuideMs
    LaunchedEffect(guide) {
        if (guide != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    val scroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    val dragged = scroll.interactionSource.collectIsDraggedAsState()
    var scrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(dragged.value, pinching) {
        if (dragged.value && !pinching) {
            ctl.player.pause()
            scrubbing = true
        }
    }
    LaunchedEffect(scrubbing, pxPerMs, pinching) {
        if (scrubbing && !pinching) {
            snapshotFlow { Triple(scroll.value, scroll.isScrollInProgress, dragged.value) }.collect { (value, moving, drag) ->
                if (!pinching) ctl.player.scrubTo((value / pxPerMs).toLong())
                if (!moving && !drag) {
                    ctl.player.endScrub()
                    scrubbing = false
                }
            }
        }
    }
    LaunchedEffect(pinching) {
        if (pinching) scroll.stopScroll()
    }
    // maxValue changes after measurement: retry against the new extent when zooming in.
    LaunchedEffect(position, pxPerMs, scrubbing, pinching, scroll.maxValue) {
        if (!scrubbing) {
            val anchor = if (pinching) zoomAnchorMs else position
            scroll.scrollTo((anchor * pxPerMs).roundToInt())
        }
    }

    BoxWithConstraints(modifier.background(Bg).testTag("timeline").pointerInput(ctl.projectId) {
        // Initial pass intercepts two-finger movement before clip/trim/scroll handlers.
        // Leave all single-finger events untouched; after a pinch consume until BOTH lift.
        awaitPointerEventScope {
            var claimed = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.count { it.pressed }
                    if (pressed >= 2) {
                        if (!claimed) {
                            claimed = true
                            zoomAnchorMs = ctl.player.positionMs.value
                            ctl.player.pause()
                            scrubbing = false
                            pinching = true
                        }
                        val factor = event.calculateZoom()
                        if (factor.isFinite() && factor > 0f) {
                            zoom = (zoom.coerceIn(currentMinZoom, currentMaxZoom) * factor)
                                .coerceIn(currentMinZoom, currentMaxZoom)
                        }
                    }
                    if (claimed) {
                        event.changes.forEach { it.consume() }
                        if (pressed == 0) {
                            claimed = false
                            pinching = false
                        }
                    }
                }
            } finally {
                pinching = false
            }
        }
    }) {
        val half = maxWidth / 2
        val viewportPx = with(density) { maxWidth.toPx() }
        val contentWidth = with(density) { (contentEndMs * pxPerMs).toDp() }

        // Only what is near the screen gets thumbnails / waveform bars / ruler labels.
        val step = 240
        val bucket by remember { derivedStateOf { scroll.value / step } }
        val visible = Visible(bucket * step - viewportPx, bucket * step + step + viewportPx)

        Row(
            Modifier
                .fillMaxHeight()
                .verticalScroll(verticalScroll)
                .horizontalScroll(scroll)
                .pointerInput(Unit) { detectTapGestures { ctl.select(null) } },
        ) {
            Spacer(Modifier.width(half))
            Box(Modifier.width(contentWidth)) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(LANE_GAP),
                ) {
                    Ruler(contentEndMs, pxPerMs, visible)
                    // Audio above the visual stack, then overlay filmstrips and the main clip.
                    val music = timeline.track(Track.MUSIC)
                    if (music.isEmpty()) {
                        // "+ Add audio" stays next to the playhead, like in the reference app
                        Box(Modifier.fillMaxWidth().height(ADD_AUDIO_HEIGHT)) {
                            Row(
                                Modifier
                                    .offset { IntOffset(scroll.value + with(density) { 8.dp.roundToPx() }, 0) }
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { ctl.library = com.videoeditor.app.data.LibraryKind.MUSIC }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("addAudio"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                                Text(" Add audio", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    FreeTrack(ctl, music, MusicColor, pxPerMs, AUDIO_LANE, visible, snapMs)
                    FreeTrack(ctl, timeline.track(Track.SFX), SfxColor, pxPerMs, AUDIO_LANE, visible, snapMs)
                    FreeTrack(ctl, timeline.track(Track.TEXT), CaptionColor, pxPerMs, AUDIO_LANE, visible, snapMs)
                    FreeTrack(ctl, timeline.track(Track.OVERLAY), OverlayColor, pxPerMs, VISUAL_LANE, visible, snapMs)
                    // main video track
                    Row(Modifier.height(MAIN_HEIGHT)) {
                        val pick = ctl.multiPick
                        val range = ctl.pickRange
                        timeline.main.forEachIndexed { index, clip ->
                            key(clip.id) {
                                val mark = when {
                                    pick == null -> PickMark.NONE
                                    clip.id in pick -> PickMark.TICKED
                                    range != null && index in range -> PickMark.INCLUDED
                                    else -> PickMark.OFF
                                }
                                MainClipView(ctl, clip, pxPerMs, visible, snapMs, mark, Modifier.testTag("mainClip_$index"))
                            }
                        }
                    }
                }
                // guide line where a dragged edge locked on
                if (guide != null) {
                    Canvas(Modifier.matchParentSize()) {
                        val x = guide * pxPerMs
                        drawLine(SnapColor, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                    }
                }
            }
            Spacer(Modifier.width(half))
        }

        // playhead
        Box(Modifier.align(Alignment.Center).padding(vertical = 4.dp).width(2.dp)
            .fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Color.White))

        // Pin the import button to the main filmstrip, including during vertical scrolling.
        val mainTop = 8.dp + RULER_HEIGHT + LANE_GAP +
            (if (timeline.track(Track.MUSIC).isEmpty()) ADD_AUDIO_HEIGHT + LANE_GAP else 0.dp) +
            laneHeight(timeline.track(Track.MUSIC), AUDIO_LANE) +
            laneHeight(timeline.track(Track.SFX), AUDIO_LANE) +
            laneHeight(timeline.track(Track.OVERLAY), VISUAL_LANE)
        val addY = with(density) { mainTop.toPx() + THUMB_HEIGHT.toPx() / 2 - 22.dp.toPx() } - verticalScroll.value
        // add more videos / images to the end of the main track
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, addY.roundToInt().coerceIn(0, (with(density) { maxHeight.toPx() - 44.dp.toPx() }).roundToInt().coerceAtLeast(0))) }
                .padding(end = 10.dp)
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .clickable(onClick = onAddMedia),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add videos or images", tint = Color.Black)
        }
    }
}

/** Time scale above the tracks (labels every 1 s ... 10 min depending on the zoom). */
@Composable
private fun Ruler(contentEndMs: Long, pxPerMs: Float, visible: Visible) {
    val density = LocalDensity.current
    val minGapPx = with(density) { 72.dp.toPx() }
    val stepMs = longArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600)
        .map { it * 1000L }
        .firstOrNull { it * pxPerMs >= minGapPx } ?: 600_000L
    val first = (visible.left / pxPerMs / stepMs).toLong().coerceAtLeast(0)
    val last = minOf((visible.right / pxPerMs / stepMs).toLong(), contentEndMs / stepMs)
    Box(Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
        for (k in first..last) {
            key(k) {
                val seconds = k * stepMs / 1000
                val label = if (seconds < 60) "${seconds}s" else String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60)
                Text(
                    label,
                    fontSize = 10.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.offset { IntOffset((k * stepMs * pxPerMs).roundToInt() + 4, 0) },
                )
                Box(
                    Modifier
                        .offset { IntOffset((k * stepMs * pxPerMs).roundToInt(), 0) }
                        .width(1.dp)
                        .height(RULER_HEIGHT)
                        .background(Color(0xFF3A3D45)),
                )
            }
        }
    }
}

/** Video / image thumbnails laid side by side over the whole width of a clip (visible part only). */
@Composable
private fun ThumbStrip(clip: Clip, pxPerMs: Float, height: Dp, visible: Visible) {
    val density = LocalDensity.current
    val tilePx = with(density) { height.toPx() }
    val widthPx = clip.durationMs * pxPerMs
    val tiles = ceil(widthPx / tilePx).toInt().coerceAtLeast(1)
    val clipStartPx = clip.timelineStartMs * pxPerMs
    val first = floor((visible.left - clipStartPx) / tilePx).toInt().coerceAtLeast(0)
    val last = floor((visible.right - clipStartPx) / tilePx).toInt().coerceAtMost(tiles - 1)
    // round to a grid that only depends on the zoom level, so frames are shared between tiles,
    // split pieces and zoom levels
    val grid = Thumbnails.gridMs((tilePx / pxPerMs).toLong())
    for (i in first..last) {
        key(i) {
            val raw = clip.sourceStartMs + ((i + 0.5f) * tilePx / pxPerMs).toLong().coerceAtMost(clip.durationMs)
            val timeMs = if (clip.kind == MediaKind.IMAGE) 0L else (raw + grid / 2) / grid * grid
            Thumb(
                clip.sourceUri, clip.kind, timeMs,
                Modifier.offset { IntOffset((i * tilePx).roundToInt(), 0) }.size(height),
            )
        }
    }
}

/** Audio waveform of a clip (bars), drawn for the visible part only. */
@Composable
private fun WaveStrip(clip: Clip, pxPerMs: Float, visible: Visible, color: Color, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val peaks by produceState<ByteArray?>(initialValue = null, clip.sourceUri) {
        value = Waveforms.load(context, clip.sourceUri)
    }
    val clipStartPx = clip.timelineStartMs * pxPerMs
    val sourceStartMs = clip.sourceStartMs
    Canvas(modifier) {
        val p = peaks ?: return@Canvas
        val stepPx = 3.dp.toPx()
        val barWidth = 2.dp.toPx()
        var x = (floor((visible.left - clipStartPx) / stepPx) * stepPx).coerceAtLeast(0f)
        val end = minOf(size.width, visible.right - clipStartPx)
        while (x < end) {
            val index = ((sourceStartMs + (x / pxPerMs).toLong()) / Waveforms.BUCKET_MS).toInt()
            if (index >= 0 && index < p.size) {
                val level = (p[index].toInt() and 0xFF) / 255f
                val h = (level * size.height).coerceAtLeast(1f)
                drawRect(color, topLeft = Offset(x, (size.height - h) / 2f), size = Size(barWidth, h))
            }
            x += stepPx
        }
    }
}

private enum class PickMark { NONE, TICKED, INCLUDED, OFF }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainClipView(ctl: EditorController, clip: Clip, pxPerMs: Float, visible: Visible, snapMs: Long, mark: PickMark, modifier: Modifier) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val widthPx = clip.durationMs * pxPerMs
    var handleBaseStart by remember { mutableStateOf(0L) }
    var handleBaseEnd by remember { mutableStateOf(0L) }

    // The outer box is exactly the clip's time span. The trim handles of the selected clip sit just
    // outside it (as in Edits), so even a very short clip keeps two separate handles and its
    // thumbnails stay visible; the selected clip is drawn above its neighbours.
    Box(
        modifier
            .width(with(density) { widthPx.toDp() })
            .fillMaxHeight()
            .zIndex(if (clip.selected) 1f else 0f),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .padding(horizontal = 1.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Bg)
                .combinedClickable(
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        ctl.longPress(clip.id)
                    },
                ) { ctl.select(clip.id) },
        ) {
            if (clip.isCompound) {
                CompoundStrips(clip, pxPerMs, visible)
            } else {
                // thumbnails on top, the clip's own sound as a waveform underneath
                Box(Modifier.fillMaxWidth().height(THUMB_HEIGHT).background(Color(0xFF22252C))) {
                    ThumbStrip(clip, pxPerMs, THUMB_HEIGHT, visible)
                }
                if (clip.hasAudio) {
                    WaveStrip(
                        clip, pxPerMs, visible, WaveColor,
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(WAVE_HEIGHT).background(WaveBackground),
                    )
                }
            }
            when (mark) {
                PickMark.TICKED, PickMark.INCLUDED -> Box(
                    Modifier.matchParentSize()
                        .background(Accent.copy(alpha = if (mark == PickMark.TICKED) 0.45f else 0.25f))
                        .border(2.dp, Accent, RoundedCornerShape(4.dp)),
                )
                PickMark.OFF -> Box(Modifier.matchParentSize().background(Color(0x66000000)))
                PickMark.NONE -> {}
            }
            if (mark == PickMark.TICKED && widthPx > with(density) { 26.dp.toPx() }) {
                Icon(
                    Icons.Filled.CheckCircle, contentDescription = "Ticked", tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(22.dp),
                )
            }
            if (clip.isCompound && !clip.selected && widthPx > with(density) { 30.dp.toPx() }) {
                CompoundBadge(clip, widthPx > with(density) { 110.dp.toPx() })
            }
            if (clip.selected && widthPx > with(density) { 46.dp.toPx() }) {
                Text(
                    String.format(java.util.Locale.US, "%.1fs", clip.durationMs / 1000f),
                    fontSize = 11.sp,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(start = 4.dp, top = 5.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        if (clip.selected) {
            // Trim handles: drag the left / right handle to hide or bring back the start / end.
            val current by rememberUpdatedState(clip)
            val scale by rememberUpdatedState(pxPerMs)
            val snap by rememberUpdatedState(snapMs)
            Box(Modifier.matchParentSize().border(2.dp, Color.White, RoundedCornerShape(2.dp)))
            TrimHandle(
                Modifier.align(Alignment.CenterStart).offset(x = -HANDLE_WIDTH)
                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)).testTag("trimStart"),
                onEnd = { ctl.endGesture() },
            ) { start, movedPx ->
                if (start) {
                    handleBaseStart = current.sourceStartMs
                    handleBaseEnd = current.sourceEndMs
                } else {
                    val deltaMs = (movedPx / scale).toLong()
                    if (current.kind == MediaKind.IMAGE) ctl.trimMain(current.id, sourceEndMs = handleBaseEnd - deltaMs)
                    else ctl.trimMain(current.id, sourceStartMs = handleBaseStart + deltaMs)
                }
            }
            TrimHandle(
                Modifier.align(Alignment.CenterEnd).offset(x = HANDLE_WIDTH)
                    .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)).testTag("trimEnd"),
                onEnd = { ctl.endGesture() },
            ) { start, movedPx ->
                if (start) {
                    handleBaseStart = current.sourceStartMs
                    handleBaseEnd = current.sourceEndMs
                } else {
                    ctl.trimMain(current.id, sourceEndMs = handleBaseEnd + (movedPx / scale).toLong(), snapMs = snap)
                }
            }
        }
    }
}

/**
 * A compound clip draws the thumbnails and sound of the pieces inside it, one after another,
 * with a thin line where one piece ends and the next begins.
 */
@Composable
private fun CompoundStrips(clip: Clip, pxPerMs: Float, visible: Visible) {
    val density = LocalDensity.current
    val pieces = remember(clip) { Compound.pieces(clip, 1f).filter { it.track == Track.MAIN } }
    Box(Modifier.fillMaxSize().background(Color(0xFF22252C))) {
        for (piece in pieces) {
            // pieces scrolled out of view are not drawn at all
            if (piece.timelineEndMs * pxPerMs < visible.left || piece.timelineStartMs * pxPerMs > visible.right) continue
            key(piece.id) {
                val offsetPx = (piece.timelineStartMs - clip.timelineStartMs) * pxPerMs
                Box(
                    Modifier
                        .offset { IntOffset(offsetPx.toInt(), 0) }
                        .width(with(density) { (piece.durationMs * pxPerMs).toDp() })
                        .fillMaxHeight(),
                ) {
                    Box(Modifier.fillMaxWidth().height(THUMB_HEIGHT).clipToBounds()) {
                        ThumbStrip(piece, pxPerMs, THUMB_HEIGHT, visible)
                    }
                    if (piece.hasAudio) {
                        WaveStrip(
                            piece, pxPerMs, visible, WaveColor,
                            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(WAVE_HEIGHT).background(WaveBackground),
                        )
                    }
                    Box(Modifier.align(Alignment.CenterStart).width(1.dp).fillMaxHeight().background(Color(0x99000000)))
                }
            }
        }
    }
}

@Composable
private fun CompoundBadge(clip: Clip, withText: Boolean) {
    Row(
        Modifier
            .padding(start = 4.dp, top = 4.dp)
            .background(Color(0xCC6D4AFF), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.MergeType, contentDescription = "Compound clip", tint = Color.White, modifier = Modifier.size(12.dp))
        if (withText) {
            val tracks = Compound.leafTracks(clip)
            val extra = tracks.filterKeys { it != Track.MAIN }.values.sum()
            val text = " ${Compound.leafCount(clip)} clips" + if (extra > 0 && clip.track == Track.MAIN) " · $extra on top" else ""
            Text(text, fontSize = 10.sp, color = Color.White, maxLines = 1)
        }
    }
}

/**
 * A free-positioned track (overlay / sound effects / music). Overlapping clips are drawn on
 * separate lanes. All clips of the track live in one box, so a clip keeps its gesture while it is
 * dragged past another clip and changes lane.
 */
@Composable
private fun FreeTrack(ctl: EditorController, clips: List<Clip>, color: Color, pxPerMs: Float, laneHeight: Dp, visible: Visible, snapMs: Long) {
    if (clips.isEmpty()) return
    val lanes = TimelineOps.lanes(clips)
    val laneCount = (lanes.values.maxOrNull() ?: 0) + 1
    Box(Modifier.fillMaxWidth().height(laneHeight * laneCount + LANE_GAP * (laneCount - 1))) {
        clips.forEachIndexed { index, clip ->
            key(clip.id) {
                val chipColor = if (clip.isCompound) CompoundColor else color
                ChipView(ctl, clip, chipColor, pxPerMs, lanes[clip.id] ?: 0, laneHeight, visible, snapMs, ctl.multiPick?.contains(clip.id) == true, "chip_${clip.track.name}_$index")
            }
        }
    }
}

/**
 * One clip on a free track. Overlay clips show thumbnails, audio clips show their waveform.
 *  - Tap: select.
 *  - Hold and drag (any time), or simply drag while selected: move it earlier / later.
 *  - While selected: drag the handles at both ends to trim where it starts / ends.
 */
@Composable
private fun ChipView(
    ctl: EditorController,
    clip: Clip,
    color: Color,
    pxPerMs: Float,
    lane: Int,
    laneHeight: Dp,
    visible: Visible,
    snapMs: Long,
    picked: Boolean,
    tag: String,
) {
    val snap by rememberUpdatedState(snapMs)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(clip)
    val scale by rememberUpdatedState(pxPerMs)
    val laneState by rememberUpdatedState(lane)
    val shape = RoundedCornerShape(5.dp)
    val laneStepPx = with(density) { (laneHeight + LANE_GAP).toPx() }
    var baseStart by remember { mutableStateOf(0L) }
    var baseEnd by remember { mutableStateOf(0L) }

    var body = Modifier
        .offset { IntOffset((current.timelineStartMs * scale).roundToInt(), (laneState * laneStepPx).roundToInt()) }
        .width(with(density) { (clip.durationMs * pxPerMs).toDp() })
        .height(laneHeight)
        .testTag(tag)
        .clip(shape)
        .background(color)
        // onLongPress here only stops the tap from also firing after a long hold; the hold itself
        // is handled by the hold-and-drag detector below
        .pointerInput(clip.id) { detectTapGestures(onLongPress = { }) { ctl.select(current.id) } }
        .pointerInput(clip.id) {
            // hold and let go: pick the clip (multi-select); hold and drag: move it
            var moved = 0f
            var dragged = false
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    baseStart = current.timelineStartMs
                    moved = 0f
                    dragged = false
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDragEnd = {
                    if (dragged) ctl.endGesture() else ctl.longPress(current.id)
                },
                onDragCancel = { ctl.endGesture() },
            ) { change, drag ->
                change.consume()
                moved += drag.x
                if (!dragged && kotlin.math.abs(moved) > viewConfiguration.touchSlop) {
                    dragged = true
                    if (!current.selected && ctl.multiPick == null) ctl.select(current.id)
                }
                if (dragged) ctl.move(current.id, baseStart + (moved / scale).toLong(), snap)
            }
        }
    if (clip.selected) {
        body = body
            .border(2.dp, Color.White, shape)
            .pointerInput(clip.id) {
                var moved = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        baseStart = current.timelineStartMs
                        moved = 0f
                    },
                    onDragEnd = { ctl.endGesture() },
                    onDragCancel = { ctl.endGesture() },
                ) { change, dx ->
                    change.consume()
                    moved += dx
                    ctl.move(current.id, baseStart + (moved / scale).toLong(), snap)
                }
            }
    }

    if (picked) body = body.border(2.dp, Accent, shape)
    Box(body) {
        if (clip.isCompound) {
            Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MergeType, contentDescription = "Compound clip", tint = Color.White, modifier = Modifier.size(12.dp))
                Text(" Compound · ${Compound.leafCount(clip)}", fontSize = 10.sp, maxLines = 1, color = Color.White)
            }
        } else if (clip.kind == MediaKind.TEXT) {
            Text(
                clip.text,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                color = Color.Black,
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 20.dp),
            )
        } else if (clip.kind == MediaKind.AUDIO) {
            WaveStrip(clip, pxPerMs, visible, Color(0x66FFFFFF), Modifier.matchParentSize().padding(vertical = 3.dp))
            Text(
                clip.name,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 22.dp),
            )
        } else {
            ThumbStrip(clip, pxPerMs, laneHeight, visible)
        }
        if (picked) {
            Box(Modifier.matchParentSize().background(Accent.copy(alpha = 0.35f)))
            Icon(
                Icons.Filled.CheckCircle, contentDescription = "Ticked", tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(16.dp),
            )
        }
        if (clip.selected) {
            TrimHandle(Modifier.align(Alignment.CenterStart).testTag("chipTrimStart"), onEnd = { ctl.endGesture() }) { start, movedPx ->
                if (start) baseStart = current.timelineStartMs
                else ctl.trimStart(current.id, baseStart + (movedPx / scale).toLong(), snap)
            }
            TrimHandle(Modifier.align(Alignment.CenterEnd).testTag("chipTrimEnd"), onEnd = { ctl.endGesture() }) { start, movedPx ->
                if (start) baseEnd = current.timelineEndMs
                else ctl.trimEnd(current.id, baseEnd + (movedPx / scale).toLong(), snap)
            }
        }
    }
}

/**
 * A trim handle at one end of a selected clip. [onDrag] is called with (true, 0) when a drag
 * starts and with (false, total pixels moved since the start) while it continues.
 */
@Composable
private fun TrimHandle(
    modifier: Modifier,
    width: Dp = HANDLE_WIDTH,
    onEnd: () -> Unit = {},
    onDrag: (start: Boolean, movedPx: Float) -> Unit,
) {
    val callback by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    Box(
        modifier
            .width(width)
            .fillMaxHeight()
            .background(Color.White)
            .pointerInput(Unit) {
                // Written out (not detectHorizontalDragGestures) so the end of the drag is always
                // reported - also when the gesture is cancelled or this node goes away mid-drag.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var started = false
                    try {
                        var moved = 0f
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            moved = over
                        }
                        if (drag != null) {
                            started = true
                            callback(true, 0f)
                            callback(false, moved)
                            horizontalDrag(drag.id) { change ->
                                moved += change.positionChange().x
                                change.consume()
                                callback(false, moved)
                            }
                        }
                    } finally {
                        if (started) end()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(3.dp).height(14.dp).background(Color.Black, RoundedCornerShape(2.dp)))
    }
}
