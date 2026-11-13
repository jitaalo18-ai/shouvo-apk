package com.videoeditor.core

data class AutoCutConfig(
    /** Pauses shorter than this are natural and are kept. */
    val minSilenceMs: Long = 600,
    /** Safety margins kept around speech so word starts / endings are not clipped. */
    val padBeforeMs: Long = 150,
    val padAfterMs: Long = 220,
    val minSegmentMs: Long = 200,
)

data class AutoCutPlan(val keep: List<MsRange>, val removedMs: Long, val cuts: Int)

/**
 * How hard AutoCut cuts. Measured on real narration (3-minute stretches): GENTLE only removes
 * long breaks, NORMAL also tightens the pauses between sentences, TIGHT removes almost every
 * pause between phrases (fast "reel" pacing). All levels keep margins around every word.
 */
enum class AutoCutStrength(val label: String, val config: AutoCutConfig) {
    GENTLE("Gentle", AutoCutConfig(minSilenceMs = 700, padBeforeMs = 150, padAfterMs = 220)),
    NORMAL("Normal", AutoCutConfig(minSilenceMs = 400, padBeforeMs = 110, padAfterMs = 160)),
    TIGHT("Tight", AutoCutConfig(minSilenceMs = 250, padBeforeMs = 80, padAfterMs = 110)),
}

/** Turns detected speech into the source ranges of a clip that should be kept. */
object AutoCutPlanner {
    /**
     * @param speech speech ranges relative to [baseSourceMs] (the source time of the first
     *   analysed audio sample).
     * @param clipStartMs / [clipEndMs] the clip's current source range.
     */
    fun plan(
        speech: List<MsRange>,
        baseSourceMs: Long,
        clipStartMs: Long,
        clipEndMs: Long,
        cfg: AutoCutConfig = AutoCutConfig(),
    ): AutoCutPlan {
        val abs = speech
            .map { MsRange(it.startMs + baseSourceMs, it.endMs + baseSourceMs) }
            .map { MsRange(it.startMs.coerceIn(clipStartMs, clipEndMs), it.endMs.coerceIn(clipStartMs, clipEndMs)) }
            .filter { it.lengthMs > 0 }
            .sortedBy { it.startMs }
        if (abs.isEmpty()) return AutoCutPlan(emptyList(), 0, 0)

        // 1. merge speech separated by short (natural) pauses
        val merged = ArrayList<MsRange>()
        for (r in abs) {
            val last = merged.lastOrNull()
            if (last != null && r.startMs - last.endMs < cfg.minSilenceMs) {
                merged[merged.lastIndex] = MsRange(last.startMs, maxOf(last.endMs, r.endMs))
            } else merged.add(r)
        }
        // 2. pad, and keep short silences at the very start / end of the clip
        val padded = merged.mapIndexed { i, r ->
            var s = r.startMs - cfg.padBeforeMs
            var e = r.endMs + cfg.padAfterMs
            if (i == 0 && r.startMs - clipStartMs < cfg.minSilenceMs) s = clipStartMs
            if (i == merged.lastIndex && clipEndMs - r.endMs < cfg.minSilenceMs) e = clipEndMs
            MsRange(s.coerceAtLeast(clipStartMs), e.coerceAtMost(clipEndMs))
        }
        // 3. join anything that touches after padding, drop slivers
        val keep = ArrayList<MsRange>()
        for (r in padded) {
            val last = keep.lastOrNull()
            if (last != null && r.startMs <= last.endMs) {
                keep[keep.lastIndex] = MsRange(last.startMs, maxOf(last.endMs, r.endMs))
            } else keep.add(r)
        }
        val finalKeep = keep.filter { it.lengthMs >= cfg.minSegmentMs }
        val kept = finalKeep.sumOf { it.lengthMs }
        return AutoCutPlan(finalKeep, (clipEndMs - clipStartMs) - kept, (finalKeep.size - 1).coerceAtLeast(0))
    }
}
