package com.videoeditor.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** Position (centre, 0..1 of the frame), zoom (1 = fitted) and rotation of a clip in the frame. */
data class FrameState(val x: Float, val y: Float, val zoom: Float, val rotation: Float)

/** A snapped [FrameState] plus which alignment guides are active. */
data class SnapResult(
    val state: FrameState,
    val centerX: Boolean,
    val centerY: Boolean,
    val left: Boolean,
    val right: Boolean,
    val top: Boolean,
    val bottom: Boolean,
    val angle: Boolean,
    val zoom: Boolean,
) {
    /** Bit mask of the active guides; a new bit means "just snapped" (time for a haptic tick). */
    val key: Int
        get() = (if (centerX) 1 else 0) or (if (centerY) 2 else 0) or (if (left) 4 else 0) or
            (if (right) 8 else 0) or (if (top) 16 else 0) or (if (bottom) 32 else 0) or
            (if (angle) 64 else 0) or (if (zoom) 128 else 0)
}

/**
 * Alignment snapping while a clip is framed with two fingers: the clip locks onto the frame
 * centre, onto the frame edges, onto straight angles (0, 90, 180, 270) and onto the "fits
 * exactly" / "fills the frame" sizes when it comes close to them.
 */
object Framing {
    const val POSITION_TOLERANCE = 0.015f     // 1.5 % of the frame
    const val ANGLE_TOLERANCE = 4f            // degrees
    const val ZOOM_TOLERANCE = 0.03f          // 3 %

    /** Size of the fitted clip as a fraction of the frame width / height. */
    fun fitFractions(clipAspect: Float, canvasAspect: Float): Pair<Float, Float> =
        if (clipAspect > canvasAspect) 1f to canvasAspect / clipAspect else clipAspect / canvasAspect to 1f

    fun snap(
        raw: FrameState,
        clipAspect: Float,
        canvasAspect: Float,
        stretchX: Float = 1f,
        stretchY: Float = 1f,
    ): SnapResult {
        val (fitW, fitH) = fitFractions(clipAspect.coerceAtLeast(0.01f), canvasAspect.coerceAtLeast(0.01f))
        val baseW = fitW * stretchX
        val baseH = fitH * stretchY

        // angle
        val straight = (raw.rotation / 90f).roundToInt() * 90f
        val angleSnap = abs(raw.rotation - straight) <= ANGLE_TOLERANCE
        val rotation = if (angleSnap) straight else raw.rotation
        val quarterTurn = angleSnap && ((straight / 90f).roundToInt() % 2 != 0)

        // on-screen size at zoom 1 (a quarter turn swaps width and height)
        val unitW = if (quarterTurn) baseH / canvasAspect else baseW
        val unitH = if (quarterTurn) baseW * canvasAspect else baseH

        // zoom: "fits exactly" and "fills the frame"
        var zoom = raw.zoom
        var zoomSnap = false
        if (angleSnap) {
            val fit = minOf(1f / unitW, 1f / unitH)
            val fill = maxOf(1f / unitW, 1f / unitH)
            for (target in floatArrayOf(fit, fill)) {
                if (!zoomSnap && abs(raw.zoom / target - 1f) <= ZOOM_TOLERANCE) {
                    zoom = target
                    zoomSnap = true
                }
            }
        }
        val w = unitW * zoom
        val h = unitH * zoom

        fun axis(value: Float, size: Float): Float = when {
            abs(value - 0.5f) <= POSITION_TOLERANCE -> 0.5f
            angleSnap && abs(value - size / 2f) <= POSITION_TOLERANCE -> size / 2f               // near edge
            angleSnap && abs(value + size / 2f - 1f) <= POSITION_TOLERANCE -> 1f - size / 2f     // far edge
            else -> value
        }
        val x = axis(raw.x, w)
        val y = axis(raw.y, h)
        val eps = 1e-4f
        return SnapResult(
            state = FrameState(x, y, zoom, rotation),
            centerX = abs(x - 0.5f) < eps,
            centerY = abs(y - 0.5f) < eps,
            left = angleSnap && abs(x - w / 2f) < eps,
            right = angleSnap && abs(x + w / 2f - 1f) < eps,
            top = angleSnap && abs(y - h / 2f) < eps,
            bottom = angleSnap && abs(y + h / 2f - 1f) < eps,
            angle = angleSnap,
            zoom = zoomSnap,
        )
    }
}
