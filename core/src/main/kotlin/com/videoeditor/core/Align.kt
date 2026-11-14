package com.videoeditor.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Snapped centre of an element (fractions of the frame) and the guides to show (see [Align]). */
data class Aligned(val x: Float, val y: Float, val guides: Int)

/**
 * Smart alignment for anything moved in the frame (video, image / video overlays, captions and
 * texts), like Instagram Edits: the element locks onto the frame centre (and its edges onto the
 * frame edges) when it comes within [Align.snap]'s tolerance, and moves freely otherwise.
 *
 * The tolerance is in screen pixels, so it feels the same in 9:16, 16:9, 1:1 and 4:5 frames.
 * Callers keep the unsnapped position of the gesture and snap it on every move - that is what
 * lets the element leave a guide again by simply moving on.
 */
object Align {
    /** Vertical guide through the frame centre (element centred left-right). */
    const val CENTER_X = 1
    /** Horizontal guide through the frame centre (element centred top-bottom). */
    const val CENTER_Y = 2
    const val LEFT = 4
    const val RIGHT = 8
    const val TOP = 16
    const val BOTTOM = 32

    /** On-screen bounding box (px) of a [w] x [h] element turned by [rotation] degrees. */
    fun boundingBox(w: Float, h: Float, rotation: Float): Pair<Float, Float> {
        val r = Math.toRadians(rotation.toDouble())
        val c = abs(cos(r)).toFloat()
        val s = abs(sin(r)).toFloat()
        return (w * c + h * s) to (w * s + h * c)
    }

    /**
     * [x], [y]: unsnapped centre (fractions of the frame). [boxW], [boxH]: the element's on-screen
     * bounding box in px; [frameW], [frameH]: the frame in px; [tolerancePx]: how close counts.
     * The centre wins over the edges when both are close.
     */
    fun snap(
        x: Float,
        y: Float,
        boxW: Float,
        boxH: Float,
        frameW: Float,
        frameH: Float,
        tolerancePx: Float,
        edges: Boolean = true,
    ): Aligned {
        var guides = 0
        fun axis(value: Float, box: Float, frame: Float, center: Int, near: Int, far: Int): Float {
            if (frame <= 0f) return value
            val tol = tolerancePx / frame
            val half = box / frame / 2f
            return when {
                abs(value - 0.5f) <= tol -> {
                    guides = guides or center
                    0.5f
                }
                edges && half < 0.5f && abs(value - half) <= tol -> {
                    guides = guides or near
                    half
                }
                edges && half < 0.5f && abs(value + half - 1f) <= tol -> {
                    guides = guides or far
                    1f - half
                }
                else -> value
            }
        }
        val sx = axis(x, boxW, frameW, CENTER_X, LEFT, RIGHT)
        val sy = axis(y, boxH, frameH, CENTER_Y, TOP, BOTTOM)
        return Aligned(sx, sy, guides)
    }

    /** Guides that were just switched on (time for a haptic tick). */
    fun newlyOn(before: Int, now: Int): Boolean = now and before.inv() != 0
}
