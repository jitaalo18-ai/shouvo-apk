package com.videoeditor.core

/** Timeline edits of the Voice enhance panel. */
object VoiceEdits {
    /** Longest fade: 5 s, and never more than half the clip. */
    const val MAX_FADE_MS = 5_000L

    fun maxFadeMs(c: Clip): Long = minOf(MAX_FADE_MS, c.durationMs / 2).coerceAtLeast(0)

    /** Clips with sound the panel works on (compound clips: open them to change what is inside). */
    fun audible(t: Timeline): List<Clip> = t.clips.filter { it.isAudible }

    /** Changes the sound settings of [ids]. */
    fun change(t: Timeline, ids: Set<String>, edit: (AudioFx) -> AudioFx): Timeline =
        Timeline(t.clips.map { if (it.id in ids && it.isAudible) it.copy(audio = clean(edit(it.audio), it)) else it })

    /** Keeps values in range (fades fit the clip). */
    fun clean(fx: AudioFx, c: Clip): AudioFx {
        val max = maxFadeMs(c)
        return fx.copy(
            volume = fx.volume.coerceIn(0f, AudioFx.MAX_VOLUME),
            denoise = fx.denoise.coerceIn(0f, 1f),
            enhance = fx.enhance.coerceIn(0f, 1f),
            fadeInMs = fx.fadeInMs.coerceIn(0, max),
            fadeOutMs = fx.fadeOutMs.coerceIn(0, max),
        )
    }

    /** "Apply enhancement to all clips": every clip gets [from]'s noise and voice settings. */
    fun enhanceAll(t: Timeline, from: AudioFx): Timeline =
        change(t, audible(t).map { it.id }.toSet()) { it.copy(denoise = from.denoise, enhance = from.enhance) }

    /** Whether every clip already has the same noise / voice settings as [from]. */
    fun allEnhancedLike(t: Timeline, from: AudioFx): Boolean {
        val list = audible(t)
        return list.size > 1 && list.all { it.audio.denoise == from.denoise && it.audio.enhance == from.enhance }
    }

    /** Noisy clips (by their analysis in [levels], keyed by clip id). */
    fun noisy(t: Timeline, levels: Map<String, AudioLevel>): List<Clip> =
        audible(t).filter { levels[it.id]?.isNoisy == true }

    /** True when every noisy clip already has noise reduction on. */
    fun noiseRemoved(t: Timeline, levels: Map<String, AudioLevel>): Boolean {
        val list = noisy(t, levels)
        return list.isNotEmpty() && list.all { it.audio.denoise > 0f }
    }

    /**
     * "Remove noise on N clips": turns noise reduction on ([AudioFx.AUTO_DENOISE]) for the noisy
     * clips; tapping again (all already on) turns it off. Returns the timeline and how many clips.
     */
    fun toggleRemoveNoise(t: Timeline, levels: Map<String, AudioLevel>): Pair<Timeline, Int> {
        val list = noisy(t, levels)
        if (list.isEmpty()) return t to 0
        val off = noiseRemoved(t, levels)
        val ids = list.map { it.id }.toSet()
        return change(t, ids) { it.copy(denoise = if (off) 0f else maxOf(it.denoise, AudioFx.AUTO_DENOISE)) } to list.size
    }

    /** "Auto adjust audio": every clip's voice to the same loudness. Returns how many clips changed. */
    fun autoAdjust(t: Timeline, levels: Map<String, AudioLevel>): Pair<Timeline, Int> {
        var n = 0
        val out = Timeline(t.clips.map { c ->
            val v = if (c.isAudible) levels[c.id]?.let { AudioAnalysis.autoVolume(it) } else null
            if (v == null) c else {
                n++
                c.copy(audio = c.audio.copy(volume = v))
            }
        })
        return out to n
    }
}
