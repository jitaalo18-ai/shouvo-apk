package com.videoeditor.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * Compound clips: several clips of any track packed into one (like a nested sequence).
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

    /** Overlays inside a main-track compound are part of that "video": drawn below the project's own overlays. */
    const val INSIDE_MAIN_LAYER = -1_000_000

    private fun relayed(clips: List<Clip>): List<Clip> {
        var t = 0L
        return clips.map { c -> c.copy(timelineStartMs = t, selected = false).also { t += c.durationMs } }
    }

    /** [c] cut down to the part from [from] to [to] (times relative to the clip's own start). */
    private fun crop(c: Clip, from: Long, to: Long): Clip =
        if (c.hasVirtualSource) c.copy(sourceStartMs = 0, sourceEndMs = to - from)
        else c.copy(sourceStartMs = c.sourceStartMs + from, sourceEndMs = c.sourceStartMs + to)

    /** Overlay layers numbered 0, 1, 2 ... in the given order (bottom first). */
    private fun renumberOverlays(clips: List<Clip>, order: List<String>): List<Clip> {
        val rank = order.withIndex().associate { (i, id) -> id to i }
        return clips.map { c -> if (c.track == Track.OVERLAY) c.copy(layer = rank[c.id] ?: c.layer) else c }
    }

    private fun overlayOrder(t: Timeline): List<String> =
        t.clips.filter { it.track == Track.OVERLAY }.sortedBy { it.layer }.map { it.id }

    /**
     * Packs the picked clips into one compound clip. Clips of any track can be picked:
     *  - with main-track clips: the compound goes on the main track and covers the run from the
     *    first to the last picked main clip (clips in between join too, so nothing moves). Picked
     *    overlay / sound / music clips go inside with it; a part of them that lies outside the
     *    compound's time stays on its track, so the project plays exactly as before.
     *  - only overlay / sound / music clips: the compound stays on their track (overlay track if
     *    any overlay is in it), from the first start to the last end.
     * Picked compound clips are opened first, so new clips can be added to an existing compound
     * and the result is still one compound (not one inside another).
     * [EditResult.reason] carries a note for the user when something could not be included.
     */
    fun create(t: Timeline, ids: Collection<String>, newId: () -> String, canvasAspect: Float): EditResult {
        var tl = t
        val picks = ids.filter { t.byId(it) != null }.toMutableSet()
        for (id in picks.toList()) {
            val c = tl.byId(id) ?: continue
            if (!c.isCompound) continue
            val (opened, parts) = open(tl, id, newId, canvasAspect) ?: continue
            tl = opened
            picks.remove(id)
            picks.addAll(parts)
        }
        val main = tl.main
        val mainIdx = main.indices.filter { main[it].id in picks }
        val free = tl.clips.filter { it.track != Track.MAIN && it.id in picks }
        val count = (if (mainIdx.isEmpty()) 0 else mainIdx.max() - mainIdx.min() + 1) + free.size
        if (count < 2) return EditResult(t, false, "Select at least two clips: long-press a clip, then tap more clips on any track.")
        return if (mainIdx.isNotEmpty()) onMain(tl, mainIdx.min(), mainIdx.max(), free, newId, canvasAspect)
        else onFreeTrack(tl, free, newId, canvasAspect)
    }

    private fun onMain(t: Timeline, from: Int, to: Int, free: List<Clip>, newId: () -> String, canvasAspect: Float): EditResult {
        val main = t.main
        val start = main[from].timelineStartMs
        val end = main[to].timelineEndMs
        val innerMain = relayed(main.subList(from, to + 1))
        val inside = ArrayList<Clip>()
        val stays = ArrayList<Clip>()      // parts of picked free clips outside the compound's time
        val staysOf = HashMap<String, MutableList<String>>()
        var leftOut = 0
        for (f in free.sortedBy { it.layer }) {
            val s = maxOf(f.timelineStartMs, start)
            val e = minOf(f.timelineEndMs, end)
            if (e <= s) {
                leftOut++
                continue
            }
            val mine = staysOf.getOrPut(f.id) { ArrayList() }
            if (f.timelineStartMs < s) {
                stays += crop(f, 0, s - f.timelineStartMs).copy(id = newId(), selected = false).also { mine += it.id }
            }
            inside += crop(f, s - f.timelineStartMs, e - f.timelineStartMs).copy(timelineStartMs = s - start, selected = false)
            if (f.timelineEndMs > e) {
                stays += crop(f, e - f.timelineStartMs, f.durationMs).copy(id = newId(), timelineStartMs = e, selected = false)
                    .also { mine += it.id }
            }
        }
        val children = innerMain + inside.mapIndexed { i, c -> if (c.track == Track.OVERLAY) c.copy(layer = i) else c }
        val total = innerMain.sumOf { it.durationMs }
        val compound = Clip(
            id = newId(),
            track = Track.MAIN,
            kind = MediaKind.COMPOUND,
            sourceUri = firstLeaf(innerMain.first()).sourceUri,
            name = NAME,
            sourceDurationMs = total,
            sourceStartMs = 0,
            sourceEndMs = total,
            timelineStartMs = start,
            hasAudio = children.any { it.hasAudio },
            aspect = canvasAspect,
            selected = true,
            children = children,
        )
        val insideIds = inside.map { it.id }.toSet()
        val newMain = main.subList(0, from).map { it.copy(selected = false) } + compound +
            main.subList(to + 1, main.size).map { it.copy(selected = false) }
        val others = t.clips.filter { it.track != Track.MAIN && it.id !in insideIds }.map { it.copy(selected = false) } +
            stays
        // the pieces that stay keep the place of their clip among the overlays
        val order = overlayOrder(t).flatMap { id -> if (id in insideIds) staysOf[id].orEmpty() else listOf(id) }
        val clips = renumberOverlays(newMain + others, order)
        val note = if (leftOut > 0) "$leftOut clip(s) outside the compound's time stayed on their track." else null
        return EditResult(TimelineOps.normalize(Timeline(clips)), true, note)
    }

    private fun onFreeTrack(t: Timeline, free: List<Clip>, newId: () -> String, canvasAspect: Float): EditResult {
        val start = free.minOf { it.timelineStartMs }
        val end = free.maxOf { it.timelineEndMs }
        val children = free.sortedBy { it.layer }.mapIndexed { i, c ->
            c.copy(timelineStartMs = c.timelineStartMs - start, selected = false, layer = if (c.track == Track.OVERLAY) i else c.layer)
        }
        val track = when {
            free.any { it.track == Track.OVERLAY } -> Track.OVERLAY
            free.any { it.track == Track.TEXT } -> Track.TEXT
            free.all { it.track == Track.MUSIC } -> Track.MUSIC
            else -> Track.SFX
        }
        val first = children.firstOrNull { it.track == Track.OVERLAY } ?: children.first()
        val compound = Clip(
            id = newId(),
            track = track,
            kind = MediaKind.COMPOUND,
            sourceUri = firstLeaf(first).sourceUri,
            name = NAME,
            sourceDurationMs = end - start,
            sourceStartMs = 0,
            sourceEndMs = end - start,
            timelineStartMs = start,
            hasAudio = children.any { it.hasAudio },
            aspect = canvasAspect,
            selected = true,
            children = children,
        )
        val picked = free.map { it.id }.toSet()
        val clips = t.clips.filter { it.id !in picked }.map { it.copy(selected = false) } + compound
        // the compound takes the place of its topmost overlay
        val topmost = free.filter { it.track == Track.OVERLAY }.maxByOrNull { it.layer }?.id
        val order = overlayOrder(t).mapNotNull { id ->
            when {
                id == topmost -> compound.id
                id in picked -> null
                else -> id
            }
        }
        return EditResult(Timeline(renumberOverlays(clips, order)), true)
    }

    /**
     * Breaks a compound clip back into its clips (one level), keeping only what is inside its
     * window and keeping how everything looks and sounds. The clips get new ids, so a compound
     * that was duplicated can be broken apart twice.
     */
    fun uncompound(t: Timeline, id: String, newId: () -> String, canvasAspect: Float): EditResult {
        val c = t.byId(id) ?: return EditResult(t, false, "Clip not found")
        if (!c.isCompound) return EditResult(t, false, "This is not a compound clip")
        val (opened, parts) = open(t, id, newId, canvasAspect) ?: return EditResult(t, false, "The compound clip is empty")
        if (parts.isEmpty()) return EditResult(t, false, "The compound clip is empty")
        return EditResult(TimelineOps.select(opened, null), true)
    }

    /** Replaces compound [id] by its parts. Returns the new timeline and the ids of the parts. */
    private fun open(t: Timeline, id: String, newId: () -> String, canvasAspect: Float): Pair<Timeline, List<String>>? {
        val c = t.byId(id) ?: return null
        if (!c.isCompound) return null
        val parts = expandOnce(c, canvasAspect) { newId() }.map { it.copy(selected = false) }
        if (parts.isEmpty()) return null
        val mainParts = parts.filter { it.track == Track.MAIN }
        val freeParts = parts.filter { it.track != Track.MAIN }
        val main = t.main.flatMap { if (it.id == id) mainParts else listOf(it) }
        val others = t.clips.filter { it.track != Track.MAIN && it.id != id } + freeParts
        val partOverlays = freeParts.filter { it.track == Track.OVERLAY }.sortedBy { it.layer }.map { it.id }
        val existing = overlayOrder(t)
        val order = if (c.track == Track.MAIN) {
            partOverlays + existing          // they were part of the main video: below the others
        } else {
            existing.flatMap { if (it == id) partOverlays else listOf(it) }
        }
        val clips = renumberOverlays(main + others, order)
        return TimelineOps.normalize(Timeline(clips)) to parts.map { it.id }
    }

    /** Every playable piece of [c] on the project timeline (a plain clip returns itself). */
    fun pieces(c: Clip, canvasAspect: Float): List<Clip> =
        if (!c.isCompound) listOf(c)
        else expandOnce(c, canvasAspect) { child -> "${c.id}/${child.id}" }.flatMap { pieces(it, canvasAspect) }

    /** The same timeline with every compound replaced by its [pieces]. */
    fun flatten(t: Timeline, canvasAspect: Float): Timeline =
        if (t.clips.none { it.isCompound }) t
        else Timeline(t.clips.flatMap { pieces(it, canvasAspect) })

    /** The top-level clip a piece of [flatten] belongs to (piece ids are "owner/child/..."). */
    fun ownerId(pieceId: String): String = pieceId.substringBefore('/')

    /** The playable main-track piece at a project time inside [c], or the nearest one. */
    fun pieceAt(c: Clip, timelineMs: Long, canvasAspect: Float): Clip {
        val all = pieces(c, canvasAspect).filter { it.track == c.track }.ifEmpty { pieces(c, canvasAspect) }
        return all.firstOrNull { it.contains(timelineMs) }
            ?: (if (timelineMs < c.timelineStartMs) all.first() else all.last())
    }

    fun firstLeaf(c: Clip): Clip {
        if (!c.isCompound || c.children.isEmpty()) return c
        val own = c.children.firstOrNull { it.track == c.track } ?: c.children.first()
        return firstLeaf(own)
    }

    /** Library items used anywhere inside [c] (not [c] itself). */
    fun libraryIds(c: Clip): Set<Long> =
        c.children.flatMap { child -> listOfNotNull(child.libraryItemId) + libraryIds(child) }.toSet()

    /** Number of plain clips inside, at any depth. */
    fun leafCount(c: Clip): Int = if (!c.isCompound) 1 else c.children.sumOf { leafCount(it) }

    /** Plain clips inside [c] per track (for the timeline label). */
    fun leafTracks(c: Clip): Map<Track, Int> =
        if (!c.isCompound) mapOf(c.track to 1)
        else c.children.map { leafTracks(it) }.fold(HashMap()) { acc, m -> m.forEach { (k, v) -> acc[k] = (acc[k] ?: 0) + v }; acc }

    /**
     * The direct children of [c] that fall inside its window: cropped, moved onto the project
     * timeline and, for a main-track compound, placed in the frame through its framing.
     */
    private fun expandOnce(c: Clip, canvasAspect: Float, idFor: (Clip) -> String): List<Clip> {
        val ws = c.sourceStartMs
        val we = c.sourceEndMs
        val overlayRank = c.children.filter { it.track == Track.OVERLAY }.sortedBy { it.layer }
            .withIndex().associate { (i, ch) -> ch.id to i }
        val out = ArrayList<Clip>()
        for (child in c.children) {
            val ls = child.timelineStartMs
            val le = ls + child.durationMs
            val s = maxOf(ls, ws)
            val e = minOf(le, we)
            if (e <= s) continue
            val cropped = crop(child, s - ls, e - ls)
            val onMain = c.track == Track.MAIN
            val placed = when {
                child.track == Track.MAIN -> place(c, cropped, canvasAspect)
                (child.track == Track.OVERLAY || child.track == Track.TEXT) && onMain -> placeOverlay(c, cropped, canvasAspect)
                else -> cropped
            }
            val layer = when {
                child.track != Track.OVERLAY -> placed.layer
                onMain -> INSIDE_MAIN_LAYER + (overlayRank[child.id] ?: 0)
                else -> c.layer
            }
            out += placed.copy(
                id = idFor(child),
                timelineStartMs = c.timelineStartMs + (s - ws),
                selected = c.selected,
                layer = layer,
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

    /** Where an overlay that was framed inside [parent]'s box ends up in the project frame. */
    internal fun placeOverlay(parent: Clip, child: Clip, canvasAspect: Float): Clip {
        val a = canvasAspect.coerceAtLeast(0.01f)
        val pa = parent.aspect.coerceAtLeast(0.01f)
        val boxW = Framing.fitFractions(pa, a).first * a * TimelineOps.mainZoom(parent)
        val boxH = boxW / pa
        val lx = (child.posX - 0.5f) * boxW * parent.stretchX
        val ly = (child.posY - 0.5f) * boxH * parent.stretchY
        val r = Math.toRadians(parent.rotation.toDouble())
        val cx = parent.posX * a + (lx * cos(r) - ly * sin(r)).toFloat()
        val cy = parent.posY + (lx * sin(r) + ly * cos(r)).toFloat()
        return child.copy(
            posX = cx / a,
            posY = cy,
            scale = child.scale * boxW / a,
            rotation = TimelineOps.normalizeAngle(parent.rotation + child.rotation),
            stretchX = child.stretchX * parent.stretchX,
            stretchY = child.stretchY * parent.stretchY,
        )
    }
}
