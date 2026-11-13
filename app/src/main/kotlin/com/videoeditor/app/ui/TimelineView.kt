package com.videoeditor.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoeditor.app.media.Waveforms
import com.videoeditor.core.Clip
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
private val WaveBackground = Color(0xFF510D50)

private fun laneHeight(clips: List<Clip>, height: Dp): Dp {
    if (clips.isEmpty()) return 0.dp
    val count = (TimelineOps.lanes(clips).values.maxOrNull() ?: 0) + 1
    return height * count + LANE_GAP * count
}

/** Keep the preview large for simple projects; scroll vertically when many tracks overlap. */
fun timelineHeight(timeline: Timeline): Dp = (RULER_HEIGHT + 28.dp + MAIN_HEIGHT +
    laneHeight(timeline.track(Track.OVERLAY), VISUAL_LANE) +
    laneHeight(timeline.track(Track.SFX), AUDIO_LANE) +
    laneHeight(timeline.track(Track.MUSIC), AUDIO_LANE)).coerceIn(164.dp, 248.dp)

/** The part of the timeline content (in pixels from its start) that is on or near the screen. */
private class Visible(val left: Float, val right: Float)

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
                if (!pinching) ctl.player.seekTo((value / pxPerMs).toLong())
                if (!moving && !drag) scrubbing = false
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

    BoxWithConstraints(modifier.background(Bg).pointerInput(ctl.projectId) {
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
            Column(
                Modifier.width(contentWidth).padding(top = 8.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(LANE_GAP),
            ) {
                Ruler(contentEndMs, pxPerMs, visible)
                // Audio above the visual stack, then overlay filmstrips and the main clip.
                FreeTrack(ctl, timeline.track(Track.MUSIC), MusicColor, pxPerMs, AUDIO_LANE, visible)
                FreeTrack(ctl, timeline.track(Track.SFX), SfxColor, pxPerMs, AUDIO_LANE, visible)
                FreeTrack(ctl, timeline.track(Track.OVERLAY), OverlayColor, pxPerMs, VISUAL_LANE, visible)
                // main video track
                Row(Modifier.height(MAIN_HEIGHT)) {
                    for (clip in timeline.main) {
                        key(clip.id) { MainClipView(ctl, clip, pxPerMs, visible) }
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
    for (i in first..last) {
        key(i) {
            val timeMs = if (clip.kind == MediaKind.IMAGE) 0L
            else clip.sourceStartMs + ((i + 0.5f) * tilePx / pxPerMs).toLong().coerceAtMost(clip.durationMs)
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

@Composable
private fun MainClipView(ctl: EditorController, clip: Clip, pxPerMs: Float, visible: Visible) {
    val density = LocalDensity.current
    val widthPx = clip.durationMs * pxPerMs
    var handleBaseStart by remember { mutableStateOf(0L) }
    var handleBaseEnd by remember { mutableStateOf(0L) }

    Box(
        Modifier
            .width(with(density) { widthPx.toDp() })
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Bg)
            .clickable { ctl.select(clip.id) },
    ) {
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
        if (clip.selected) {
            // Trim handles: drag the left / right handle to hide or bring back the start / end.
            val current by rememberUpdatedState(clip)
            val scale by rememberUpdatedState(pxPerMs)
            TrimHandle(Modifier.align(Alignment.CenterStart)) { start, movedPx ->
                if (start) {
                    handleBaseStart = current.sourceStartMs
                    handleBaseEnd = current.sourceEndMs
                } else {
                    val deltaMs = (movedPx / scale).toLong()
                    if (current.kind == MediaKind.IMAGE) ctl.trimMain(current.id, sourceEndMs = handleBaseEnd - deltaMs)
                    else ctl.trimMain(current.id, sourceStartMs = handleBaseStart + deltaMs)
                }
            }
            TrimHandle(Modifier.align(Alignment.CenterEnd)) { start, movedPx ->
                if (start) {
                    handleBaseStart = current.sourceStartMs
                    handleBaseEnd = current.sourceEndMs
                } else {
                    ctl.trimMain(current.id, sourceEndMs = handleBaseEnd + (movedPx / scale).toLong())
                }
            }
            Box(Modifier.matchParentSize().border(2.dp, Color.White, RoundedCornerShape(4.dp)))
            Text(
                String.format(java.util.Locale.US, "%.1fs", clip.durationMs / 1000f),
                fontSize = 11.sp,
                color = Color.White,
                modifier = Modifier
                    .padding(start = 24.dp, top = 5.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * A free-positioned track (overlay / sound effects / music). Overlapping clips are drawn on
 * separate lanes. All clips of the track live in one box, so a clip keeps its gesture while it is
 * dragged past another clip and changes lane.
 */
@Composable
private fun FreeTrack(ctl: EditorController, clips: List<Clip>, color: Color, pxPerMs: Float, laneHeight: Dp, visible: Visible) {
    if (clips.isEmpty()) return
    val lanes = TimelineOps.lanes(clips)
    val laneCount = (lanes.values.maxOrNull() ?: 0) + 1
    Box(Modifier.fillMaxWidth().height(laneHeight * laneCount + LANE_GAP * (laneCount - 1))) {
        for (clip in clips) {
            key(clip.id) { ChipView(ctl, clip, color, pxPerMs, lanes[clip.id] ?: 0, laneHeight, visible) }
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
) {
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
        .clip(shape)
        .background(color)
        .pointerInput(clip.id) { detectTapGestures { ctl.select(current.id) } }
        .pointerInput(clip.id) {
            var moved = 0f
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    baseStart = current.timelineStartMs
                    moved = 0f
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (!current.selected) ctl.select(current.id)
                },
            ) { change, drag ->
                change.consume()
                moved += drag.x
                ctl.move(current.id, baseStart + (moved / scale).toLong())
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
                ) { change, dx ->
                    change.consume()
                    moved += dx
                    ctl.move(current.id, baseStart + (moved / scale).toLong())
                }
            }
    }

    Box(body) {
        if (clip.kind == MediaKind.AUDIO) {
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
        if (clip.selected) {
            TrimHandle(Modifier.align(Alignment.CenterStart)) { start, movedPx ->
                if (start) baseStart = current.timelineStartMs
                else ctl.trimStart(current.id, baseStart + (movedPx / scale).toLong())
            }
            TrimHandle(Modifier.align(Alignment.CenterEnd)) { start, movedPx ->
                if (start) baseEnd = current.timelineEndMs
                else ctl.trimEnd(current.id, baseEnd + (movedPx / scale).toLong())
            }
        }
    }
}

/**
 * A trim handle at one end of a selected clip. [onDrag] is called with (true, 0) when a drag
 * starts and with (false, total pixels moved since the start) while it continues.
 */
@Composable
private fun TrimHandle(modifier: Modifier, width: Dp = HANDLE_WIDTH, onDrag: (start: Boolean, movedPx: Float) -> Unit) {
    val callback by rememberUpdatedState(onDrag)
    Box(
        modifier
            .width(width)
            .fillMaxHeight()
            .background(Color.White)
            .pointerInput(Unit) {
                var moved = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        moved = 0f
                        callback(true, 0f)
                    },
                ) { change, dx ->
                    change.consume()
                    moved += dx
                    callback(false, moved)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(3.dp).height(14.dp).background(Color.Black, RoundedCornerShape(2.dp)))
    }
}
