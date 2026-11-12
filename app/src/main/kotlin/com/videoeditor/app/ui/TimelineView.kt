package com.videoeditor.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoeditor.core.Clip
import com.videoeditor.core.MediaKind
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val MAIN_HEIGHT = 64.dp
private val LANE_HEIGHT = 34.dp
private val LANE_GAP = 4.dp
private val HANDLE_WIDTH = 18.dp

/**
 * Scrollable timeline with a fixed playhead in the middle (the content moves under it).
 * Dragging the timeline scrubs the video; during playback the timeline follows the player.
 */
@Composable
fun TimelineView(ctl: EditorController, position: Long, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val timeline = ctl.timeline
    val contentEndMs = maxOf(timeline.durationMs, timeline.clips.maxOfOrNull { it.timelineEndMs } ?: 0L, 1L)
    // 60 dp per second, reduced for very long projects so the row stays within layout limits.
    val pxPerMs = remember(contentEndMs, density) {
        minOf(with(density) { 60.dp.toPx() } / 1000f, 200_000f / contentEndMs)
    }

    val scroll = rememberScrollState()
    val dragged = scroll.interactionSource.collectIsDraggedAsState()
    var scrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(dragged.value) {
        if (dragged.value) {
            ctl.player.pause()
            scrubbing = true
        }
    }
    LaunchedEffect(scrubbing, pxPerMs) {
        if (scrubbing) {
            snapshotFlow { Triple(scroll.value, scroll.isScrollInProgress, dragged.value) }.collect { (value, moving, drag) ->
                ctl.player.seekTo((value / pxPerMs).toLong())
                if (!moving && !drag) scrubbing = false
            }
        }
    }
    LaunchedEffect(position, pxPerMs, scrubbing) {
        if (!scrubbing) scroll.scrollTo((position * pxPerMs).roundToInt())
    }

    BoxWithConstraints(modifier.background(Bg)) {
        val half = maxWidth / 2
        val viewportPx = with(density) { maxWidth.toPx() }
        val contentWidth = with(density) { (contentEndMs * pxPerMs).toDp() }

        Row(
            Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(scroll)
                .pointerInput(Unit) { detectTapGestures { ctl.select(null) } },
        ) {
            Spacer(Modifier.width(half))
            Column(
                Modifier.width(contentWidth).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // overlays, sound effects and music sit above the main video track
                FreeTrack(ctl, timeline.track(Track.OVERLAY), OverlayColor, pxPerMs)
                FreeTrack(ctl, timeline.track(Track.SFX), SfxColor, pxPerMs)
                FreeTrack(ctl, timeline.track(Track.MUSIC), MusicColor, pxPerMs)
                // main video track
                Row(Modifier.height(MAIN_HEIGHT)) {
                    for (clip in timeline.main) {
                        key(clip.id) { MainClipView(ctl, clip, pxPerMs, scroll, viewportPx) }
                    }
                }
            }
            Spacer(Modifier.width(half))
        }

        // playhead
        Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().background(Color.White))
    }
}

@Composable
private fun MainClipView(ctl: EditorController, clip: Clip, pxPerMs: Float, scroll: ScrollState, viewportPx: Float) {
    val density = LocalDensity.current
    val widthPx = clip.durationMs * pxPerMs
    val tilePx = with(density) { MAIN_HEIGHT.toPx() }
    val tiles = ceil(widthPx / tilePx).toInt().coerceAtLeast(1)
    val clipStartPx = clip.timelineStartMs * pxPerMs

    var handleBaseStart by remember { mutableStateOf(0L) }
    var handleBaseEnd by remember { mutableStateOf(0L) }

    // Only the thumbnails near the visible part of the timeline are composed.
    val step = 240
    val bucket by remember { derivedStateOf { scroll.value / step } }
    val visibleLeft = bucket * step - viewportPx
    val visibleRight = bucket * step + step + viewportPx
    val first = floor((visibleLeft - clipStartPx) / tilePx).toInt().coerceAtLeast(0)
    val last = floor((visibleRight - clipStartPx) / tilePx).toInt().coerceAtMost(tiles - 1)

    Box(
        Modifier
            .width(with(density) { widthPx.toDp() })
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF22252C))
            .clickable { ctl.select(clip.id) },
    ) {
        for (i in first..last) {
            key(i) {
                val timeMs = if (clip.kind == MediaKind.IMAGE) 0L
                else clip.sourceStartMs + ((i + 0.5f) * tilePx / pxPerMs).toLong().coerceAtMost(clip.durationMs)
                Thumb(
                    clip.sourceUri, clip.kind, timeMs,
                    Modifier.offset { IntOffset((i * tilePx).roundToInt(), 0) }.size(MAIN_HEIGHT),
                )
            }
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
            Box(Modifier.matchParentSize().border(3.dp, Accent, RoundedCornerShape(6.dp)))
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
private fun FreeTrack(ctl: EditorController, clips: List<Clip>, color: Color, pxPerMs: Float) {
    if (clips.isEmpty()) return
    val lanes = TimelineOps.lanes(clips)
    val laneCount = (lanes.values.maxOrNull() ?: 0) + 1
    Box(Modifier.fillMaxWidth().height(LANE_HEIGHT * laneCount + LANE_GAP * (laneCount - 1))) {
        for (clip in clips) {
            key(clip.id) { ChipView(ctl, clip, color, pxPerMs, lanes[clip.id] ?: 0) }
        }
    }
}

/**
 * One clip on a free track.
 *  - Tap: select.
 *  - Hold and drag (any time), or simply drag while selected: move it earlier / later.
 *  - While selected: drag the handles at both ends to trim where it starts / ends.
 */
@Composable
private fun ChipView(ctl: EditorController, clip: Clip, color: Color, pxPerMs: Float, lane: Int) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(clip)
    val scale by rememberUpdatedState(pxPerMs)
    val laneState by rememberUpdatedState(lane)
    val shape = RoundedCornerShape(6.dp)
    val laneStepPx = with(density) { (LANE_HEIGHT + LANE_GAP).toPx() }
    var baseStart by remember { mutableStateOf(0L) }
    var baseEnd by remember { mutableStateOf(0L) }

    var body = Modifier
        .offset { IntOffset((current.timelineStartMs * scale).roundToInt(), (laneState * laneStepPx).roundToInt()) }
        .width(with(density) { (clip.durationMs * pxPerMs).toDp() })
        .height(LANE_HEIGHT)
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
            .border(2.dp, Accent, shape)
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
        Text(
            clip.name,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 22.dp),
        )
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
            .background(Accent)
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
        Box(Modifier.width(3.dp).height(14.dp).background(Color.White, RoundedCornerShape(2.dp)))
    }
}
