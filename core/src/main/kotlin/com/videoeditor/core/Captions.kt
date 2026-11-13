package com.videoeditor.core

/** One piece of recognised speech: [startMs]..[endMs] on the project timeline. */
data class SpeechPiece(val startMs: Long, val endMs: Long, val text: String)

/**
 * Turns recognised speech into caption clips on the TEXT track (Auto Captions).
 *
 * The speech is recognised from the project's own sound as it plays (the main track, pieces in
 * timeline order), so the times of the pieces are already timeline times.
 */
object Captions {
    const val AUTO_NAME = "Caption"
    const val TEXT_NAME = "Text"
    const val MIN_MS = 400L
    const val MAX_MS = 7_000L
    const val DEFAULT_STYLE = "CLASSIC"
    const val DEFAULT_SIZE = 0.05f       // text height as a part of the frame height
    const val DEFAULT_Y = 0.78f

    /** Things speech recognisers write when nobody speaks. */
    private val noise = Regex(
        """^[\s\p{Punct}।॥♪♫]*$|^\s*[\[(（].{0,30}[\])）]\s*$""",
    )

    fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    fun isNoise(text: String): Boolean = clean(text).isEmpty() || noise.matches(clean(text))

    /**
     * Caption clips for [pieces]: empty and "(music)" pieces are dropped, every caption shows at
     * least [MIN_MS] (unless the next one starts first) and at most [MAX_MS], and captions never
     * overlap, so they stay in one row.
     */
    fun build(
        pieces: List<SpeechPiece>,
        projectMs: Long,
        newId: () -> String,
        style: String = DEFAULT_STYLE,
        posY: Float = DEFAULT_Y,
        size: Float = DEFAULT_SIZE,
    ): List<Clip> {
        val kept = pieces.map { it.copy(text = clean(it.text)) }
            .filter { !isNoise(it.text) && it.endMs > it.startMs && it.startMs < projectMs }
            .sortedBy { it.startMs }
        val out = ArrayList<Clip>()
        for ((i, p) in kept.withIndex()) {
            val start = maxOf(p.startMs, out.lastOrNull()?.timelineEndMs ?: 0L).coerceAtLeast(0)
            val nextStart = kept.getOrNull(i + 1)?.startMs ?: projectMs
            val wanted = maxOf(p.endMs, start + MIN_MS).coerceAtMost(start + MAX_MS)
            val end = minOf(wanted, maxOf(nextStart, start + 1), projectMs)
            if (end - start < 100) continue
            out += textClip(newId(), start, end - start, p.text, AUTO_NAME, style, posY, size)
        }
        return out
    }

    fun textClip(
        id: String,
        startMs: Long,
        durationMs: Long,
        text: String,
        name: String = TEXT_NAME,
        style: String = DEFAULT_STYLE,
        posY: Float = DEFAULT_Y,
        size: Float = DEFAULT_SIZE,
    ) = Clip(
        id = id,
        track = Track.TEXT,
        kind = MediaKind.TEXT,
        sourceUri = "",
        name = name,
        sourceDurationMs = 0,
        sourceStartMs = 0,
        sourceEndMs = durationMs,
        timelineStartMs = startMs,
        hasAudio = false,
        aspect = 1f,
        posX = 0.5f,
        posY = posY,
        scale = size,
        text = text,
        style = style,
    )

    /** Replaces the earlier auto captions (not texts the user typed) with [captions]. */
    fun replaceAuto(t: Timeline, captions: List<Clip>): Timeline =
        Timeline(t.clips.filter { !(it.track == Track.TEXT && it.name == AUTO_NAME) }.map { it.copy(selected = false) } + captions)

    /** Gives every caption / text in [ids] (all TEXT clips if null) the same look and place. */
    fun restyle(t: Timeline, ids: Set<String>?, style: String? = null, posY: Float? = null, size: Float? = null): Timeline =
        Timeline(t.clips.map { c ->
            if (c.kind != MediaKind.TEXT || (ids != null && c.id !in ids)) c
            else c.copy(style = style ?: c.style, posY = posY ?: c.posY, scale = size ?: c.scale)
        })

    fun setText(t: Timeline, id: String, text: String): Timeline =
        Timeline(t.clips.map { if (it.id == id && it.kind == MediaKind.TEXT) it.copy(text = text) else it })
}
