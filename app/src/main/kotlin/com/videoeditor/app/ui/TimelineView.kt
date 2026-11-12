package com.videoeditor.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
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

private val MAIN_HEIGHT = 56.dp
private val LANE_HEIGHT = 30.dp

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
                // main video track
                Row(Modifier.height(MAIN_HEIGHT)) {
                    for (clip in timeline.main) {
                        key(clip.id) { MainClipView(ctl, clip, pxPerMs, scroll, viewportPx) }
                    }
                }
                // overlay track(s), then sound effects, then background music
                FreeTrack(ctl, timeline.track(Track.OVERLAY), OverlayColor, pxPerMs)
                FreeTrack(ctl, timeline.track(Track.SFX), SfxColor, pxPerMs)
                FreeTrack(ctl, timeline.track(Track.MUSIC), MusicColor, pxPerMs)
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
            Box(Modifier.matchParentSize().border(2.dp, Color.White, RoundedCornerShape(6.dp)))
        }
    }
}

/** A free-positioned track (overlay / sound effects / music). Overlapping clips get their own lane. */
@Composable
private fun FreeTrack(ctl: EditorController, clips: List<Clip>, color: Color, pxPerMs: Float) {
    if (clips.isEmpty()) return
    val lanes = TimelineOps.lanes(clips)
    val laneCount = (lanes.values.maxOrNull() ?: 0) + 1
    for (lane in 0 until laneCount) {
        Box(Modifier.fillMaxWidth().height(LANE_HEIGHT)) {
            for (clip in clips) {
                if (lanes[clip.id] == lane) {
                    key(clip.id) { ChipView(ctl, clip, color, pxPerMs) }
                }
            }
        }
    }
}

/**
 * One clip on a free track. Tap to select. When selected: drag the body to move it along the
 * timeline, drag the white handles to change when it starts / ends.
 */
@Composable
private fun ChipView(ctl: EditorController, clip: Clip, color: Color, pxPerMs: Float) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(clip)
    val scale by rememberUpdatedState(pxPerMs)
    val shape = RoundedCornerShape(6.dp)

    var body = Modifier
        .offset { IntOffset((current.timelineStartMs * scale).roundToInt(), 0) }
        .width(with(density) { (clip.durationMs * pxPerMs).toDp() })
        .fillMaxHeight()
        .clip(shape)
        .background(color)
        .pointerInput(clip.id) { detectTapGestures { ctl.select(current.id) } }
    if (clip.selected) {
        body = body
            .border(2.dp, Color.White, shape)
            .pointerInput(clip.id) {
                var base = 0L
                var moved = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        base = current.timelineStartMs
                        moved = 0f
                    },
                ) { change, dx ->
                    change.consume()
                    moved += dx
                    ctl.move(current.id, base + (moved / scale).toLong())
                }
            }
    }

    Box(body) {
        Text(
            clip.name,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp),
        )
        if (clip.selected) {
            val handle = Modifier.width(14.dp).fillMaxHeight().background(Color.White)
            Box(
                handle.align(Alignment.CenterStart).pointerInput(clip.id) {
                    var base = 0L
                    var moved = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            base = current.timelineStartMs
                            moved = 0f
                        },
                    ) { change, dx ->
                        change.consume()
                        moved += dx
                        ctl.trimStart(current.id, base + (moved / scale).toLong())
                    }
                },
            )
            Box(
                handle.align(Alignment.CenterEnd).pointerInput(clip.id) {
                    var base = 0L
                    var moved = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            base = current.timelineEndMs
                            moved = 0f
                        },
                    ) { change, dx ->
                        change.consume()
                        moved += dx
                        ctl.trimEnd(current.id, base + (moved / scale).toLong())
                    }
                },
            )
        }
    }
}
