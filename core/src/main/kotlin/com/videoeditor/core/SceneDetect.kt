package com.videoeditor.core

import kotlin.math.abs

/**
 * "Split scenes" (like CapCut's): finds the hard cuts inside a video - the frames where one shot
 * ends and another begins - so the clip can be split there.
 *
 * Every frame is given as a tiny brightness picture ([W] x [H] values 0..255). A cut is a frame
 * that differs from the one before both in its brightness histogram and pixel by pixel, clearly
 * more than the frames around it differ from each other (so fast motion and camera pans inside
 * a shot are not cuts). Slow fades / dissolves between shots are not found.
 */
class SceneDetector {
    private var prev: ByteArray? = null
    private var prevHist = FloatArray(BINS)
    private val recent = ArrayDeque<Float>()
    private var lastCutMs = Long.MIN_VALUE / 2
    private var firstMs = -1L
    private val found = ArrayList<Long>()
    // a candidate waits for the next frame: a single odd frame (flash) is not a cut
    private var pendingMs = -1L
    private var pendingBefore: ByteArray? = null
    private var pendingDiff = 0f

    /** The cuts so far: the time of the first frame of each new shot. */
    val cuts: List<Long> get() = found

    /** Frames must come in order; [luma] has [W] * [H] values. */
    fun feed(timeMs: Long, luma: ByteArray) {
        require(luma.size == W * H)
        if (firstMs < 0) firstMs = timeMs
        val hist = histogram(luma)
        val before = prev
        if (before != null) {
            // a pending cut is real only if the new frame is still unlike the frame before the cut
            val p = pendingBefore
            var backFromFlash = false
            if (p != null) {
                if (pixelDiff(p, luma) >= pendingDiff * 0.5f) {
                    found += pendingMs
                    lastCutMs = pendingMs
                    recent.clear()
                } else {
                    backFromFlash = true        // the odd frame is over: this jump back is no cut either
                }
                pendingBefore = null
            }
            var dh = 0f
            for (i in 0 until BINS) dh += abs(hist[i] - prevHist[i])
            dh /= 2f
            val dp = pixelDiff(before, luma)
            val score = dh + dp
            val usual = if (recent.isEmpty()) 0f else recent.sorted()[recent.size / 2]
            val isCut = !backFromFlash && dh >= HIST_MIN && dp >= PIXEL_MIN && score >= usual * RATIO + MARGIN &&
                timeMs - lastCutMs >= MIN_SCENE_MS && timeMs - firstMs >= MIN_SCENE_MS
            if (isCut) {
                pendingMs = timeMs
                pendingBefore = before
                pendingDiff = dp
            } else if (!backFromFlash) {
                recent.addLast(score)
                if (recent.size > WINDOW) recent.removeFirst()
            }
        }
        prev = luma.copyOf()
        prevHist = hist
    }

    /** Call after the last frame; [endMs] is the end of the clip (a cut right at the end is dropped). */
    fun finish(endMs: Long): List<Long> {
        pendingBefore = null
        return found.filter { endMs - it >= MIN_SCENE_MS }
    }

    private fun histogram(luma: ByteArray): FloatArray {
        val h = FloatArray(BINS)
        for (b in luma) h[(b.toInt() and 0xFF) * BINS / 256] += 1f
        for (i in 0 until BINS) h[i] /= luma.size
        return h
    }

    private fun pixelDiff(a: ByteArray, b: ByteArray): Float {
        var s = 0
        for (i in a.indices) s += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        return s / (a.size * 255f)
    }

    companion object {
        const val W = 32
        const val H = 32
        const val BINS = 32
        /** At least this much of the histogram moved (0..1). */
        const val HIST_MIN = 0.20f
        /** Average pixel change (0..1). */
        const val PIXEL_MIN = 0.07f
        /** ... and this many times the usual change between frames just before, plus a margin. */
        const val RATIO = 2.5f
        const val MARGIN = 0.08f
        const val WINDOW = 12
        /** Shots shorter than this are not split off. */
        const val MIN_SCENE_MS = 400L
    }
}

object SceneSplit {
    /**
     * Splits clip [clipId] at the [cutsMs] (timeline times). Returns the new timeline and the
     * number of pieces the clip became (1 = unchanged).
     */
    fun apply(t: Timeline, clipId: String, cutsMs: List<Long>, newId: () -> String): Pair<Timeline, Int> {
        var timeline = t
        var current = clipId
        var pieces = 1
        for (cut in cutsMs.sorted()) {
            var right = ""
            val r = TimelineOps.split(timeline, current, cut) { newId().also { right = it } }
            if (!r.changed) continue
            timeline = r.timeline
            current = right
            pieces++
        }
        return timeline to pieces
    }
}
