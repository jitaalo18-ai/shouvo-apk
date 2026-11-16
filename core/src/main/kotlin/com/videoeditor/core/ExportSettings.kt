package com.videoeditor.core

import kotlin.math.roundToInt

/**
 * What the Export sheet offers (like CapCut's): the resolution and the frame rate of the video.
 * [fps] 0 = as the videos are. A chosen frame rate only ever leaves frames out (a 30 fps video
 * exported at 60 stays 30: no frames are invented).
 */
data class ExportSettings(val shortSide: Int = 1080, val fps: Int = 0) {
    val resolutionLabel: String get() = RESOLUTIONS.firstOrNull { it.first == shortSide }?.second ?: "${shortSide}p"
    /** The label next to the Export button. */
    val badge: String
        get() = when (shortSide) {
            480 -> "SD"
            720 -> "HD"
            1080 -> "FHD"
            1440 -> "2K"
            2160 -> "4K"
            else -> "${shortSide}p"
        }
    val fpsLabel: String get() = if (fps == 0) "Auto" else "$fps"
    val isDefault: Boolean get() = shortSide == 1080 && fps == 0

    /** Output frame size for a project of [aspect]: short side [shortSide], long side at most 16:9 of it, even numbers. */
    fun canvasSize(aspect: Float): Pair<Int, Int> {
        val a = if (aspect.isFinite() && aspect > 0f) aspect else 16f / 9f
        val short = shortSide.toFloat()
        var w: Float
        var h: Float
        if (a >= 1f) {
            h = short
            w = h * a
        } else {
            w = short
            h = w / a
        }
        val cap = (shortSide * 16f / 9f / 2f).roundToInt() * 2f      // 1080 -> 1920, 1440 -> 2560, 2160 -> 3840
        val long = maxOf(w, h)
        if (long > cap) {
            val k = cap / long
            w *= k
            h *= k
        }
        fun even(v: Float) = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
        return even(w) to even(h)
    }

    fun encode(): String = "$shortSide,$fps"

    companion object {
        val RESOLUTIONS = listOf(480 to "480p", 720 to "720p", 1080 to "1080p", 1440 to "2K", 2160 to "4K")
        val FRAME_RATES = listOf(0, 24, 25, 30, 50, 60)

        fun decode(s: String?): ExportSettings {
            val p = s?.split(',') ?: return ExportSettings()
            val side = p.getOrNull(0)?.toIntOrNull()?.takeIf { v -> RESOLUTIONS.any { it.first == v } } ?: 1080
            val fps = p.getOrNull(1)?.toIntOrNull()?.takeIf { it in FRAME_RATES } ?: 0
            return ExportSettings(side, fps)
        }
    }
}

/** "Export video" for one clip: the part of the project under that clip, as a project of its own. */
object ExportRange {
    /**
     * Everything that is seen and heard between [startMs] and [endMs] of [t] (clips of every
     * track cut to that part), moved to start at 0. Each piece is cut the way Split cuts.
     */
    fun slice(t: Timeline, startMs: Long, endMs: Long): Timeline {
        require(endMs > startMs)
        val out = ArrayList<Clip>()
        for (c in t.clips) {
            val from = maxOf(c.timelineStartMs, startMs)
            val to = minOf(c.timelineEndMs, endMs)
            if (to - from < MIN_PIECE_MS && !(c.timelineStartMs >= startMs && c.timelineEndMs <= endMs)) continue
            if (to <= from) continue
            val cutStart = from - c.timelineStartMs
            val cutEnd = c.timelineEndMs - to
            out += c.copy(
                sourceStartMs = c.sourceStartMs + cutStart,
                sourceEndMs = c.sourceEndMs - cutEnd,
                timelineStartMs = from - startMs,
                selected = false,
                audio = c.audio.copy(
                    fadeInMs = if (cutStart > 0) 0 else c.audio.fadeInMs,
                    fadeOutMs = if (cutEnd > 0) 0 else c.audio.fadeOutMs,
                ),
            )
        }
        return TimelineOps.normalize(Timeline(out))
    }

    /** The part under clip [clipId], or null when there is no such clip. */
    fun ofClip(t: Timeline, clipId: String): Timeline? {
        val c = t.byId(clipId) ?: return null
        return slice(t, c.timelineStartMs, c.timelineEndMs)
    }

    const val MIN_PIECE_MS = 40L
}

/**
 * Which frames a chosen frame rate keeps: time is cut into slots of 1 / fps; the first frame
 * that falls into a new slot is kept, further frames in the same slot are left out. A video at
 * or below the chosen rate keeps every frame.
 */
class FrameRateLimiter(private val fps: Int) {
    private var lastSlot = Long.MIN_VALUE
    private var lastUs = Long.MIN_VALUE

    fun keep(timeUs: Long): Boolean {
        if (fps <= 0) return true
        val slot = Math.floorDiv(timeUs * fps + 500_000L, 1_000_000L)
        // time going back = a new stream: start again
        val keep = slot > lastSlot || timeUs < lastUs
        lastUs = timeUs
        if (keep) lastSlot = slot
        return keep
    }

    fun reset() {
        lastSlot = Long.MIN_VALUE
        lastUs = Long.MIN_VALUE
    }
}
