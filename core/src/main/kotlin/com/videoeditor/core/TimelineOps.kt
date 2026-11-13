package com.videoeditor.core

/**
 * Pure, non-destructive timeline edits. Every function returns a new [Timeline]; source media is
 * never touched. This file is the Split Engine, the timeline half of the AutoCut Engine and the
 * positioning logic for overlays / sound effects / music.
 */
object TimelineOps {
    const val MIN_SPLIT_MS = 100L
    const val MIN_TRIM_MS = 200L
    const val MAX_IMAGE_MS = 10 * 60 * 1000L
    const val MIN_SCALE = 0.05f
    const val MAX_SCALE = 3f

    /** Re-lays the main track so clips are contiguous from 0 in the given order. */
    private fun relay(mainOrdered: List<Clip>, others: List<Clip>): Timeline {
        var t = 0L
        val laid = mainOrdered.map { c ->
            val out = c.copy(timelineStartMs = t)
            t += c.durationMs
            out
        }
        return Timeline(laid + others)
    }

    private fun others(t: Timeline) = t.clips.filter { it.track != Track.MAIN }

    fun normalize(t: Timeline): Timeline = relay(t.main, others(t))

    /** Timeline time -> (main clip, source timestamp). Times past the end map to the last frame. */
    fun locate(t: Timeline, timelineMs: Long): MainPosition? {
        val main = t.main
        if (main.isEmpty()) return null
        val ms = timelineMs.coerceAtLeast(0)
        main.forEachIndexed { i, c ->
            if (ms < c.timelineEndMs) return MainPosition(i, c, c.timelineToSource(ms))
        }
        val last = main.last()
        return MainPosition(main.lastIndex, last, last.sourceEndMs)
    }

    /**
     * Splits [clipId] at [timelineMs] into two independent clips that reference the same source.
     * The left part keeps the original id; the right part gets [newId].
     */
    fun split(t: Timeline, clipId: String, timelineMs: Long, newId: () -> String): EditResult {
        val clip = t.byId(clipId) ?: return EditResult(t, false, "Clip not found")
        if (timelineMs < clip.timelineStartMs + MIN_SPLIT_MS ||
            timelineMs > clip.timelineEndMs - MIN_SPLIT_MS
        ) {
            return EditResult(t, false, "Move the playhead inside the selected clip to split it")
        }
        val cutSource = clip.sourceStartMs + (timelineMs - clip.timelineStartMs)
        val left = clip.copy(sourceEndMs = cutSource)
        val right = clip.copy(
            id = newId(),
            sourceStartMs = cutSource,
            timelineStartMs = timelineMs,
            selected = false,
        )
        val out = t.clips.flatMap { if (it.id == clipId) listOf(left, right) else listOf(it) }
        val result = Timeline(out)
        return EditResult(if (clip.track == Track.MAIN) normalize(result) else result, true)
    }

    /**
     * AutoCut application: replaces a main clip with the given source ranges (the parts to keep),
     * joined in chronological order. Audio and video stay in sync because every piece is still
     * "source time X..Y" of the same file. An empty keep list is rejected - content is never
     * silently discarded.
     */
    fun applyKeepRanges(
        t: Timeline,
        clipId: String,
        keep: List<MsRange>,
        newId: () -> String,
    ): EditResult {
        val clip = t.byId(clipId) ?: return EditResult(t, false, "Clip not found")
        if (clip.track != Track.MAIN) return EditResult(t, false, "Not a main-track clip")
        if (keep.isEmpty()) return EditResult(t, false, "Nothing to keep")
        var prevEnd = clip.sourceStartMs
        for (r in keep) {
            if (r.startMs < prevEnd || r.endMs <= r.startMs || r.endMs > clip.sourceEndMs) {
                return EditResult(t, false, "Invalid keep ranges")
            }
            prevEnd = r.endMs
        }
        if (keep.size == 1 && keep[0].startMs == clip.sourceStartMs &&
            keep[0].endMs == clip.sourceEndMs
        ) {
            return EditResult(t, false, "Nothing to remove")
        }
        val pieces = keep.mapIndexed { i, r ->
            clip.copy(
                id = if (i == 0) clip.id else newId(),
                sourceStartMs = r.startMs,
                sourceEndMs = r.endMs,
                selected = i == 0 && clip.selected,
            )
        }
        val main = t.main.flatMap { if (it.id == clipId) pieces else listOf(it) }
        return EditResult(relay(main, others(t)), true)
    }

    fun appendMain(t: Timeline, clips: List<Clip>): Timeline =
        relay(t.main + clips.map { it.copy(track = Track.MAIN) }, others(t))

    /**
     * Inserts [clip] into the main track at [timelineMs]. If the playhead is inside a clip, that
     * clip is split and the new clip goes between the two halves.
     */
    fun insertMainAt(t: Timeline, timelineMs: Long, clip: Clip, newId: () -> String): Timeline {
        val incoming = clip.copy(track = Track.MAIN, selected = false)
        val pos = locate(t, timelineMs) ?: return relay(listOf(incoming), others(t))
        val target = pos.clip
        val main = t.main.toMutableList()
        val idx = pos.index
        when {
            timelineMs >= t.durationMs || timelineMs > target.timelineEndMs - MIN_SPLIT_MS ->
                main.add(idx + 1, incoming)
            timelineMs < target.timelineStartMs + MIN_SPLIT_MS -> main.add(idx, incoming)
            else -> {
                val cut = target.timelineToSource(timelineMs)
                main[idx] = target.copy(sourceEndMs = cut)
                main.add(idx + 1, incoming)
                main.add(idx + 2, target.copy(id = newId(), sourceStartMs = cut, selected = false))
            }
        }
        return relay(main, others(t))
    }

    /** Adds a free-positioned clip (overlay / sound effect / music). Overlays go on top. */
    fun addClip(t: Timeline, clip: Clip): Timeline {
        require(clip.track != Track.MAIN) { "Use appendMain/insertMainAt for main clips" }
        val layer = if (clip.track == Track.OVERLAY) {
            (t.clips.filter { it.track == Track.OVERLAY }.maxOfOrNull { it.layer } ?: -1) + 1
        } else 0
        return Timeline(t.clips + clip.copy(layer = layer, timelineStartMs = clip.timelineStartMs.coerceAtLeast(0)))
    }

    private fun update(t: Timeline, id: String, f: (Clip) -> Clip): Timeline =
        Timeline(t.clips.map { if (it.id == id) f(it) else it })

    /** Moves a non-main clip along the timeline. */
    fun move(t: Timeline, id: String, newStartMs: Long): Timeline = update(t, id) {
        if (it.track == Track.MAIN) it else it.copy(timelineStartMs = newStartMs.coerceAtLeast(0))
    }

    /** Changes when a non-main clip starts (drags its left edge). */
    fun trimStart(t: Timeline, id: String, newTimelineStartMs: Long): Timeline = update(t, id) { c ->
        if (c.track == Track.MAIN) return@update c
        val wanted = newTimelineStartMs.coerceAtLeast(0)
        if (c.kind == MediaKind.IMAGE) {
            val start = wanted.coerceIn(
                (c.timelineEndMs - MAX_IMAGE_MS).coerceAtLeast(0),
                c.timelineEndMs - MIN_TRIM_MS,
            )
            c.copy(timelineStartMs = start, sourceStartMs = 0, sourceEndMs = c.timelineEndMs - start)
        } else {
            val delta = wanted - c.timelineStartMs
            val newSourceStart = (c.sourceStartMs + delta).coerceIn(0, c.sourceEndMs - MIN_TRIM_MS)
            val applied = newSourceStart - c.sourceStartMs
            c.copy(sourceStartMs = newSourceStart, timelineStartMs = c.timelineStartMs + applied)
        }
    }

    /** Changes when a non-main clip ends (drags its right edge). */
    fun trimEnd(t: Timeline, id: String, newTimelineEndMs: Long): Timeline = update(t, id) { c ->
        if (c.track == Track.MAIN) return@update c
        val wantedDur = newTimelineEndMs - c.timelineStartMs
        if (c.kind == MediaKind.IMAGE) {
            c.copy(sourceStartMs = 0, sourceEndMs = wantedDur.coerceIn(MIN_TRIM_MS, MAX_IMAGE_MS))
        } else {
            val end = (c.sourceStartMs + wantedDur)
                .coerceIn(c.sourceStartMs + MIN_TRIM_MS, maxOf(c.sourceDurationMs, c.sourceStartMs + MIN_TRIM_MS))
            c.copy(sourceEndMs = end)
        }
    }

    /** Removes a clip. Main clips re-flow so the track stays gap-less. */
    fun remove(t: Timeline, id: String): Timeline = normalize(Timeline(t.clips.filter { it.id != id }))

    fun select(t: Timeline, id: String?): Timeline =
        Timeline(t.clips.map { it.copy(selected = id != null && it.id == id) })

    /**
     * Trims a main-track clip by setting a new source start and / or end (the yellow-handle
     * gesture). The main track re-flows, so no gap appears. Nothing is cut from the file itself:
     * dragging the handle back out restores the hidden part.
     */
    fun trimMain(t: Timeline, id: String, newSourceStartMs: Long? = null, newSourceEndMs: Long? = null): Timeline {
        val clip = t.byId(id) ?: return t
        if (clip.track != Track.MAIN) return t
        val trimmed = if (clip.kind == MediaKind.IMAGE) {
            // an image has no source limits; only its duration changes
            val duration = ((newSourceEndMs ?: clip.sourceEndMs) - (newSourceStartMs ?: 0L))
                .coerceIn(MIN_TRIM_MS, MAX_IMAGE_MS)
            clip.copy(sourceStartMs = 0, sourceEndMs = duration)
        } else {
            var start = clip.sourceStartMs
            var end = clip.sourceEndMs
            if (newSourceStartMs != null) start = newSourceStartMs.coerceIn(0, end - MIN_SPLIT_MS)
            if (newSourceEndMs != null) {
                end = newSourceEndMs.coerceIn(start + MIN_SPLIT_MS, maxOf(clip.sourceDurationMs, start + MIN_SPLIT_MS))
            }
            clip.copy(sourceStartMs = start, sourceEndMs = end)
        }
        return relay(t.main.map { if (it.id == id) trimmed else it }, others(t))
    }

    /** Moves a main-track clip one place earlier (-1) or later (+1). */
    fun moveMain(t: Timeline, id: String, delta: Int): Timeline {
        val main = t.main.toMutableList()
        val from = main.indexOfFirst { it.id == id }
        if (from < 0) return t
        val to = (from + delta).coerceIn(0, main.lastIndex)
        if (to == from) return t
        main.add(to, main.removeAt(from))
        return relay(main, others(t))
    }

    const val MIN_STRETCH = 0.25f
    const val MAX_STRETCH = 4f

    /** Makes a clip wider / taller without changing the other direction (1 = original shape). */
    fun stretch(t: Timeline, id: String, stretchX: Float, stretchY: Float): Timeline = update(t, id) {
        it.copy(
            stretchX = stretchX.coerceIn(MIN_STRETCH, MAX_STRETCH),
            stretchY = stretchY.coerceIn(MIN_STRETCH, MAX_STRETCH),
        )
    }

    /** Keeps an angle in (-180, 180]. */
    fun normalizeAngle(degrees: Float): Float {
        if (!degrees.isFinite()) return 0f
        var a = degrees % 360f
        if (a > 180f) a -= 360f
        if (a <= -180f) a += 360f
        return a
    }

    /** Overlay position / size / rotation in canvas-relative units. */
    fun transform(t: Timeline, id: String, posX: Float, posY: Float, scale: Float, rotation: Float? = null): Timeline =
        update(t, id) {
            it.copy(
                posX = posX.coerceIn(0f, 1f),
                posY = posY.coerceIn(0f, 1f),
                scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
                rotation = if (rotation == null) it.rotation else normalizeAngle(rotation),
            )
        }

    /**
     * Main-track clips reuse [Clip.scale] for their zoom inside the frame: the stored default
     * (0.4, the same default overlays get) means "fitted, zoom 1". Keeping this mapping avoids a
     * database change for projects created before zoom existed.
     */
    const val MAIN_SCALE_UNIT = 0.4f
    const val MIN_MAIN_ZOOM = 0.2f
    const val MAX_MAIN_ZOOM = 6f

    fun mainZoom(c: Clip): Float = c.scale / MAIN_SCALE_UNIT

    /** Zoom / move a main clip inside the frame. posX/posY are the centre (0..1), zoom 1 = fitted. */
    fun transformMain(t: Timeline, id: String, posX: Float, posY: Float, zoom: Float, rotation: Float? = null): Timeline =
        update(t, id) {
            if (it.track != Track.MAIN) it else it.copy(
                posX = posX.coerceIn(0f, 1f),
                posY = posY.coerceIn(0f, 1f),
                scale = zoom.coerceIn(MIN_MAIN_ZOOM, MAX_MAIN_ZOOM) * MAIN_SCALE_UNIT,
                rotation = if (rotation == null) it.rotation else normalizeAngle(rotation),
            )
        }

    /** Copies a main clip; the copy goes right after it and is selected. */
    fun duplicateMain(t: Timeline, id: String, newId: () -> String): Timeline {
        val c = t.byId(id) ?: return t
        if (c.track != Track.MAIN) return t
        val copy = c.copy(id = newId(), selected = true)
        val main = t.main.flatMap { if (it.id == id) listOf(it.copy(selected = false), copy) else listOf(it.copy(selected = false)) }
        return relay(main, others(t).map { it.copy(selected = false) })
    }

    /**
     * Pastes a copied clip at [atMs]: a main clip is inserted there (a clip under the playhead is
     * split), any other clip starts there on its own track. The pasted clip is selected.
     */
    fun paste(t: Timeline, clip: Clip, atMs: Long, newId: () -> String): Timeline {
        val incoming = clip.copy(id = newId(), selected = false)
        val placed = if (clip.track == Track.MAIN) {
            insertMainAt(t, atMs.coerceIn(0, t.durationMs), incoming, newId)
        } else {
            addClip(t, incoming.copy(timelineStartMs = atMs.coerceAtLeast(0)))
        }
        return select(placed, incoming.id)
    }

    /** Copies a free-track clip (overlay / sound / music). The copy goes on top and is selected. */
    fun duplicate(t: Timeline, id: String, newId: () -> String): Timeline {
        val c = t.byId(id) ?: return t
        if (c.track == Track.MAIN) return t
        val copy = c.copy(
            id = newId(),
            posX = (c.posX + 0.05f).coerceIn(0f, 1f),
            posY = (c.posY + 0.05f).coerceIn(0f, 1f),
            selected = false,
        )
        return select(addClip(t, copy), copy.id)
    }

    /** Display lanes for a free track so overlapping clips are drawn on separate rows. */
    fun lanes(clips: List<Clip>): Map<String, Int> {
        val laneEnds = ArrayList<Long>()
        val out = LinkedHashMap<String, Int>()
        for (c in clips.sortedWith(compareBy({ it.timelineStartMs }, { it.id }))) {
            var lane = laneEnds.indexOfFirst { it <= c.timelineStartMs }
            if (lane < 0) {
                laneEnds.add(c.timelineEndMs)
                lane = laneEnds.lastIndex
            } else {
                laneEnds[lane] = c.timelineEndMs
            }
            out[c.id] = lane
        }
        return out
    }
}
