package com.videoeditor.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * Compound clips: several main-track clips packed into one (like a nested sequence).
 *
 * A compound clip keeps its clips in [Clip.children] on an inner timeline starting at 0, and its
 * own sourceStartMs..sourceEndMs is a window into that inner timeline. Because of that, the
 * ordinary edits (split, trim, move, delete, duplicate) work on a compound unchanged.
 *
 * Playback and export never see compounds: [flatten] replaces every compound by the pieces of
 * its children that are inside the window, already placed on the project timeline and in the
 * frame. The compound's own position / zoom / rotation / stretch apply to everything inside it:
 * its box has the frame shape it was made with (aspect), and identity = the full frame.
 */
object Compound {
    const val NAME = "Compound"

    private fun relayed(clips: List<Clip>): List<Clip> {
        var t = 0L
        return clips.map { c -> c.copy(timelineStartMs = t, selected = false).also { t += c.durationMs } }
    }

    /**
     * Packs main clips into one compound clip. The compound covers the run from the first to
     * the last of [ids] on the main track (clips in between are included, so the result plays
     * exactly as before). Nothing about other tracks changes, because the timing stays the same.
     */
    fun create(t: Timeline, ids: Collection<String>, newId: () -> String, canvasAspect: Float): EditResult {
        val main = t.main
        val indices = ids.map { id -> main.indexOfFirst { it.id == id } }.filter { it >= 0 }
        if (indices.isEmpty()) return EditResult(t, false, "Select the clips to put into the compound clip")
        val from = indices.min()
        val to = indices.max()
        if (from == to) return EditResult(t, false, "Select at least two clips")
        val inside = relayed(main.subList(from, to + 1))
        val total = inside.sumOf { it.durationMs }
        val first = firstLeaf(inside.first())
        val compound = Clip(
            id = newId(),
            track = Track.MAIN,
            kind = MediaKind.COMPOUND,
            sourceUri = first.sourceUri,
            mimeType = null,
            name = NAME,
            sourceDurationMs = total,
            sourceStartMs = 0,
            sourceEndMs = total,
            timelineStartMs = main[from].timelineStartMs,
            hasAudio = inside.any { it.hasAudio },
            aspect = canvasAspect,
            selected = true,
            children = inside,
        )
        val newMain = main.subList(0, from).map { it.copy(selected = false) } + compound +
            main.subList(to + 1, main.size).map { it.copy(selected = false) }
        val others = t.clips.filter { it.track != Track.MAIN }.map { it.copy(selected = false) }
        return EditResult(TimelineOps.normalize(Timeline(newMain + others)), true)
    }

    /**
     * Breaks a compound clip back into its clips (one level), keeping only what is inside its
     * window and keeping how everything looks in the frame. The clips get new ids, so a compound
     * that was duplicated can be broken apart twice.
     */
    fun uncompound(t: Timeline, id: String, newId: () -> String, canvasAspect: Float): EditResult {
        val c = t.byId(id) ?: return EditResult(t, false, "Clip not found")
        if (!c.isCompound) return EditResult(t, false, "This is not a compound clip")
        val parts = expandOnce(c, canvasAspect) { newId() }.map { it.copy(selected = false) }
        if (parts.isEmpty()) return EditResult(t, false, "The compound clip is empty")
        val main = t.main.flatMap { if (it.id == id) parts else listOf(it) }
        return EditResult(TimelineOps.normalize(Timeline(main + t.clips.filter { it.track != Track.MAIN })), true)
    }

    /** Every playable piece of [c] on the project timeline (a plain clip returns itself). */
    fun pieces(c: Clip, canvasAspect: Float): List<Clip> =
        if (!c.isCompound) listOf(c)
        else expandOnce(c, canvasAspect) { child -> "${c.id}/${child.id}" }.flatMap { pieces(it, canvasAspect) }

    /** The same timeline with every compound replaced by its [pieces]. */
    fun flatten(t: Timeline, canvasAspect: Float): Timeline =
        if (t.clips.none { it.isCompound }) t
        else Timeline(t.clips.flatMap { pieces(it, canvasAspect) })

    /** The playable piece at a project time inside [c], or the nearest one. */
    fun pieceAt(c: Clip, timelineMs: Long, canvasAspect: Float): Clip {
        val all = pieces(c, canvasAspect)
        return all.firstOrNull { it.contains(timelineMs) }
            ?: (if (timelineMs < c.timelineStartMs) all.first() else all.last())
    }

    fun firstLeaf(c: Clip): Clip = if (c.isCompound && c.children.isNotEmpty()) firstLeaf(c.children.first()) else c

    /** Library items used anywhere inside [c] (not [c] itself). */
    fun libraryIds(c: Clip): Set<Long> =
        c.children.flatMap { child -> listOfNotNull(child.libraryItemId) + libraryIds(child) }.toSet()

    /** Number of plain clips inside, at any depth. */
    fun leafCount(c: Clip): Int = if (!c.isCompound) 1 else c.children.sumOf { leafCount(it) }

    /**
     * The direct children of [c] that fall inside its window: cropped, moved onto the project
     * timeline and placed in the frame through the compound's own transform.
     */
    private fun expandOnce(c: Clip, canvasAspect: Float, idFor: (Clip) -> String): List<Clip> {
        val ws = c.sourceStartMs
        val we = c.sourceEndMs
        val out = ArrayList<Clip>()
        for (child in c.children) {
            val ls = child.timelineStartMs
            val le = ls + child.durationMs
            val s = maxOf(ls, ws)
            val e = minOf(le, we)
            if (e <= s) continue
            val cropped = if (child.kind == MediaKind.IMAGE) {
                child.copy(sourceStartMs = 0, sourceEndMs = e - s)
            } else {
                child.copy(sourceStartMs = child.sourceStartMs + (s - ls), sourceEndMs = child.sourceStartMs + (e - ls))
            }
            out += place(c, cropped, canvasAspect).copy(
                id = idFor(child),
                timelineStartMs = c.timelineStartMs + (s - ws),
                selected = c.selected,
                layer = c.layer,
            )
        }
        return out
    }

    /**
     * Where [child] (framed inside [parent]'s box) ends up in the project frame. Exact for any
     * position / zoom / rotation; when the parent is stretched unevenly and the child is turned
     * at the same time the stretch is applied along the child's own axes (close, not exact).
     */
    internal fun place(parent: Clip, child: Clip, canvasAspect: Float): Clip {
        val a = canvasAspect.coerceAtLeast(0.01f)
        val pa = parent.aspect.coerceAtLeast(0.01f)
        val ca = child.aspect.coerceAtLeast(0.01f)
        // parent box in a frame of height 1 and width a
        val boxW = Framing.fitFractions(pa, a).first * a * TimelineOps.mainZoom(parent)   // unstretched
        val boxH = boxW / pa
        // child inside the unstretched box
        val childW = Framing.fitFractions(ca, pa).first * boxW * TimelineOps.mainZoom(child)
        val lx = (child.posX - 0.5f) * boxW * parent.stretchX
        val ly = (child.posY - 0.5f) * boxH * parent.stretchY
        val r = Math.toRadians(parent.rotation.toDouble())
        val cx = parent.posX * a + (lx * cos(r) - ly * sin(r)).toFloat()
        val cy = parent.posY + (lx * sin(r) + ly * cos(r)).toFloat()
        val fitInFrame = Framing.fitFractions(ca, a).first * a
        return child.copy(
            posX = cx / a,
            posY = cy,
            scale = childW / fitInFrame * TimelineOps.MAIN_SCALE_UNIT,
            rotation = TimelineOps.normalizeAngle(parent.rotation + child.rotation),
            stretchX = child.stretchX * parent.stretchX,
            stretchY = child.stretchY * parent.stretchY,
        )
    }
}
