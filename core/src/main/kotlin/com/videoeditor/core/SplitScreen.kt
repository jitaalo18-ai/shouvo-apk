package com.videoeditor.core

/**
 * Split screen (like CapCut on PC): the pictures that are on screen at the same moment - the
 * main clip and the overlays above it - each get their own part of the frame instead of lying
 * on top of each other. Every picture fills its part completely: it is scaled up until the part
 * is covered and what sticks out is cropped away evenly (nothing is squeezed).
 */
object SplitScreen {
    /** A part of the frame, as fractions of its width and height. */
    data class Cell(val x: Float, val y: Float, val w: Float, val h: Float)

    data class Layout(val id: String, val name: String, val cells: List<Cell>)

    const val MAX_CLIPS = 9

    /** The pictures a split screen is made of at [timeMs]: the main clip first, then overlays bottom to top. */
    fun clipsAt(t: Timeline, timeMs: Long): List<Clip> {
        fun visual(c: Clip) = c.kind == MediaKind.VIDEO || c.kind == MediaKind.IMAGE
        val main = t.main.firstOrNull { it.contains(timeMs) && visual(it) }
        val over = t.clips.filter { it.track == Track.OVERLAY && it.contains(timeMs) && visual(it) }
            .sortedWith(compareBy<Clip> { it.layer }.thenBy { it.timelineStartMs })
        return (listOfNotNull(main) + over).take(MAX_CLIPS)
    }

    private fun columns(n: Int) = List(n) { Cell(it / n.toFloat(), 0f, 1f / n, 1f) }
    private fun rows(n: Int) = List(n) { Cell(0f, it / n.toFloat(), 1f, 1f / n) }
    private fun grid(n: Int, cols: Int): List<Cell> {
        val rowsN = (n + cols - 1) / cols
        return List(n) { i ->
            val r = i / cols
            // a last row with fewer pictures shares the whole width
            val inRow = if (r == rowsN - 1) n - r * cols else cols
            val c = i - r * cols
            Cell(c / inRow.toFloat(), r / rowsN.toFloat(), 1f / inRow, 1f / rowsN)
        }
    }

    /** The layouts offered for [n] pictures (2 and more). */
    fun layouts(n: Int): List<Layout> = when {
        n < 2 -> emptyList()
        n == 2 -> listOf(Layout("cols", "Side by side", columns(2)), Layout("rows", "Top and bottom", rows(2)))
        n == 3 -> listOf(
            Layout("cols", "3 columns", columns(3)),
            Layout("rows", "3 rows", rows(3)),
            Layout("big-left", "1 + 2", listOf(Cell(0f, 0f, 0.5f, 1f), Cell(0.5f, 0f, 0.5f, 0.5f), Cell(0.5f, 0.5f, 0.5f, 0.5f))),
            Layout("big-top", "1 over 2", listOf(Cell(0f, 0f, 1f, 0.5f), Cell(0f, 0.5f, 0.5f, 0.5f), Cell(0.5f, 0.5f, 0.5f, 0.5f))),
        )
        n == 4 -> listOf(
            Layout("grid", "2 x 2", grid(4, 2)),
            Layout("rows", "4 rows", rows(4)),
            Layout("cols", "4 columns", columns(4)),
            Layout("big-top", "1 over 3", listOf(Cell(0f, 0f, 1f, 0.5f)) + List(3) { Cell(it / 3f, 0.5f, 1f / 3, 0.5f) }),
        )
        else -> listOf(
            Layout("grid2", "2 across", grid(n, 2)),
            Layout("grid3", "3 across", grid(n, 3)),
            Layout("rows", "$n rows", rows(n)),
        )
    }

    /** [clip] filling [cell] of a frame with [canvasAspect] (cover: scaled up, the overhang cropped). */
    fun fit(clip: Clip, cell: Cell, canvasAspect: Float): Clip {
        val a = clip.aspect.coerceAtLeast(0.01f)
        val cellAspect = (cell.w * canvasAspect / cell.h).coerceAtLeast(0.001f)
        val cropX = if (a > cellAspect) (cellAspect / a).coerceIn(MIN_CROP, 1f) else 1f
        val cropY = if (a > cellAspect) 1f else (a / cellAspect).coerceIn(MIN_CROP, 1f)
        // shown width = whole width x cropX = the cell's width
        val wholeWidth = cell.w / cropX                              // of the frame width
        val scale = if (clip.track == Track.MAIN) {
            val (fitW, _) = Framing.fitFractions(a, canvasAspect.coerceAtLeast(0.01f))
            wholeWidth / fitW * TimelineOps.MAIN_SCALE_UNIT          // zoom 1 = fitted
        } else wholeWidth
        return clip.copy(
            posX = cell.x + cell.w / 2, posY = cell.y + cell.h / 2,
            scale = scale, rotation = 0f, stretchX = 1f, stretchY = 1f, cropX = cropX, cropY = cropY,
        )
    }

    /** Lays [ids] (in this order) out in [layout]. Clips beyond the layout's parts stay as they are. */
    fun apply(t: Timeline, ids: List<String>, layout: Layout, canvasAspect: Float): Timeline {
        val cellOf = ids.zip(layout.cells).toMap()
        return Timeline(t.clips.map { c -> cellOf[c.id]?.let { fit(c, it, canvasAspect) } ?: c })
    }

    /** Takes the split screen back: whole pictures again, the main clip fitted, overlays in the middle. */
    fun reset(t: Timeline, ids: Collection<String>): Timeline = Timeline(t.clips.map { c ->
        if (c.id !in ids) c else c.copy(
            posX = 0.5f, posY = 0.5f, rotation = 0f, stretchX = 1f, stretchY = 1f, cropX = 1f, cropY = 1f,
            scale = if (c.track == Track.MAIN) TimelineOps.MAIN_SCALE_UNIT else 0.4f,
        )
    })

    /** Where a clip is shown: left, top, width, height as fractions of the frame (rotation ignored). */
    fun shownBox(c: Clip, canvasAspect: Float): Cell {
        val a = c.aspect.coerceAtLeast(0.01f)
        val whole = if (c.track == Track.MAIN) Framing.fitFractions(a, canvasAspect).first * TimelineOps.mainZoom(c) else c.scale
        val w = whole * c.stretchX * c.cropX
        val h = whole / a * canvasAspect * c.stretchY * c.cropY
        return Cell(c.posX - w / 2, c.posY - h / 2, w, h)
    }

    const val MIN_CROP = 0.02f
}
