package com.videoeditor.core

import kotlin.math.abs

/** Conservative passthrough: only an uncut video with isolated audio and no visual edits. */
object ExportPolicy {
    fun canCopyVideo(timeline: Timeline, aspect: Float): Boolean {
        val c = timeline.clips.singleOrNull() ?: return false
        return c.track == Track.MAIN && c.kind == MediaKind.VIDEO && c.isAudible && c.audio.isolate &&
            c.sourceStartMs == 0L && c.sourceEndMs == c.sourceDurationMs && c.timelineStartMs == 0L &&
            abs(c.aspect - aspect) < 0.001f && TimelineOps.mainZoom(c) == 1f &&
            c.posX == 0.5f && c.posY == 0.5f && c.rotation == 0f && c.stretchX == 1f && c.stretchY == 1f && !c.isCropped
    }

    /** Room for temporary output AND the Gallery copy, plus audio and a safety reserve. */
    fun requiredSpace(videoBytes: Long, durationMs: Long): Long {
        require(videoBytes >= 0 && durationMs >= 0)
        return Math.addExact(Math.multiplyExact(videoBytes, 2),
            Math.addExact(Math.multiplyExact(durationMs, 48), 128L * 1024 * 1024))
    }
}
