package com.videoeditor.core

import kotlin.math.abs

/** Result of a snapped position: the value to use and the guide it locked onto (or null). */
data class Snapped(val value: Long, val guideMs: Long?)

/**
 * Magnet snapping on the timeline: while a clip is moved or trimmed its edges lock onto the
 * playhead, the start of the timeline, the edges of the main-track clips and the edges of the
 * other clips when they come within [threshold] milliseconds.
 */
object Snap {
    fun targets(t: Timeline, excludeId: String?, playheadMs: Long): List<Long> {
        val out = HashSet<Long>()
        out.add(0L)
        out.add(playheadMs)
        for (c in t.clips) {
            if (c.id == excludeId) continue
            out.add(c.timelineStartMs)
            out.add(c.timelineEndMs)
        }
        return out.sorted()
    }

    /**
     * Targets for the right edge of main-track clip [id]. The main track ripples: every clip after
     * it moves together with the edge (the next clip always starts exactly at it), so their edges
     * are not targets, or the edge would lock onto itself and feel sticky.
     */
    fun mainEdgeTargets(t: Timeline, id: String, playheadMs: Long): List<Long> {
        val index = t.main.indexOfFirst { it.id == id }
        if (index < 0) return targets(t, id, playheadMs)
        val moving = t.main.drop(index).map { it.id }.toSet()
        val out = HashSet<Long>()
        out.add(0L)
        out.add(playheadMs)
        for (c in t.clips) {
            if (c.id in moving) continue
            out.add(c.timelineStartMs)
            out.add(c.timelineEndMs)
        }
        return out.sorted()
    }

    /** Closest target within [threshold] of [value], or null. */
    fun nearest(value: Long, targets: List<Long>, threshold: Long): Long? {
        var best: Long? = null
        var bestDist = threshold + 1
        for (t in targets) {
            val d = abs(t - value)
            if (d <= threshold && d < bestDist) {
                best = t
                bestDist = d
            }
        }
        return best
    }

    /** Moving a clip of [duration]: its start or its end may lock onto a target. */
    fun move(rawStart: Long, duration: Long, targets: List<Long>, threshold: Long): Snapped {
        val s = nearest(rawStart, targets, threshold)
        val e = nearest(rawStart + duration, targets, threshold)
        val ds = s?.let { abs(it - rawStart) } ?: Long.MAX_VALUE
        val de = e?.let { abs(it - (rawStart + duration)) } ?: Long.MAX_VALUE
        return when {
            s != null && ds <= de -> Snapped(s.coerceAtLeast(0), s)
            e != null -> Snapped((e - duration).coerceAtLeast(0), e)
            else -> Snapped(rawStart.coerceAtLeast(0), null)
        }
    }

    /** Dragging one edge (trim handle) to [raw]. */
    fun edge(raw: Long, targets: List<Long>, threshold: Long): Snapped {
        val s = nearest(raw, targets, threshold)
        return if (s != null) Snapped(s, s) else Snapped(raw, null)
    }
}
