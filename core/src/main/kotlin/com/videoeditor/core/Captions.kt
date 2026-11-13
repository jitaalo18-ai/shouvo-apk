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

    /**
     * Speech recognisers often start a piece at the beginning of their listening window, i.e. in
     * the silence before the words. Moves the start / end of every piece onto the speech that
     * really is inside it ([speech] from the voice detector), so a caption appears when the words
     * are said - not before. Pieces without detected speech are left as they are.
     */
    fun alignToSpeech(pieces: List<SpeechPiece>, speech: List<MsRange>, padBeforeMs: Long = 120, padAfterMs: Long = 250): List<SpeechPiece> =
        pieces.map { p ->
            val inside = speech.filter { it.endMs > p.startMs && it.startMs < p.endMs }
            if (inside.isEmpty()) return@map p
            val start = maxOf(p.startMs, inside.first().startMs - padBeforeMs)
            val end = minOf(p.endMs, inside.last().endMs + padAfterMs)
            if (end - start >= 200) p.copy(startMs = start, endMs = end) else p
        }

    /**
     * Cuts the sound into parts of at most [maxMs] for the speech recogniser, at pauses: a
     * recogniser can only write so much text per part, and Bangla needs a lot of it. Long
     * silences between speech are left out (faster, and nothing gets invented there). Speech
     * without a pause is cut at the quietest moment that [quietest] finds between [minMs] and
     * [maxMs] into the part. Without [speech] (the voice detector was unsure) the whole sound is
     * cut the same way.
     */
    fun planChunks(
        totalMs: Long,
        speech: List<MsRange>,
        quietest: (fromMs: Long, toMs: Long) -> Long,
        maxMs: Long = 15_000,
        minMs: Long = 8_000,
        padMs: Long = 300,
    ): List<MsRange> {
        val regions = if (speech.isEmpty()) listOf(MsRange(0, totalMs))
        else speech.map { MsRange((it.startMs - padMs).coerceAtLeast(0), (it.endMs + padMs).coerceAtMost(totalMs)) }
        // join regions that fit into one part
        val joined = ArrayList<MsRange>()
        for (r in regions.sortedBy { it.startMs }) {
            val last = joined.lastOrNull()
            if (last != null && r.startMs <= last.endMs + 1_500 && r.endMs - last.startMs <= maxMs) {
                joined[joined.lastIndex] = MsRange(last.startMs, maxOf(last.endMs, r.endMs))
            } else if (last != null && r.startMs < last.endMs) {
                joined += MsRange(last.endMs, r.endMs)            // overlapping pads
            } else {
                joined += r
            }
        }
        // cut parts that are still too long at their quietest moment
        val out = ArrayList<MsRange>()
        for (r in joined) {
            var start = r.startMs
            while (r.endMs - start > maxMs) {
                val cut = quietest(start + minMs, start + maxMs).coerceIn(start + minMs, start + maxMs)
                out += MsRange(start, cut)
                start = cut
            }
            if (r.endMs - start >= 200) out += MsRange(start, r.endMs)
        }
        return out
    }

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
