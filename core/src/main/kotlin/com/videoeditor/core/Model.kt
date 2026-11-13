package com.videoeditor.core

/** Timeline layers. MAIN is the gap-less primary video track; the others are free-positioned. TEXT holds captions and text. */
enum class Track { MAIN, OVERLAY, SFX, MUSIC, TEXT }

/** COMPOUND = a clip that holds other clips (see [Compound]); TEXT = a caption or text on the TEXT track. */
enum class MediaKind { VIDEO, IMAGE, AUDIO, COMPOUND, TEXT }

/** Half-open range in milliseconds: [startMs, endMs). */
data class MsRange(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/**
 * One non-destructive editing decision: "play [sourceStartMs, sourceEndMs) of sourceUri at
 * timelineStartMs". The source media is never modified.
 *
 * For IMAGE clips the source range is virtual (0..duration).
 * posX/posY/scale are only used by OVERLAY clips: centre of the overlay in the canvas (0..1) and
 * overlay width as a fraction of the canvas width.
 */
data class Clip(
    val id: String,
    val track: Track,
    val kind: MediaKind,
    val sourceUri: String,
    val mimeType: String? = null,
    val name: String = "",
    val libraryItemId: Long? = null,
    val sourceDurationMs: Long,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val timelineStartMs: Long,
    val hasAudio: Boolean,
    val aspect: Float = 1f,
    val layer: Int = 0,
    val posX: Float = 0.5f,
    val posY: Float = 0.5f,
    val scale: Float = 0.4f,
    /** Rotation in degrees, clockwise on screen, in (-180, 180]. Main clips and overlays. */
    val rotation: Float = 0f,
    /** Extra width / height factors (1 = unchanged) to make the picture wider or taller. */
    val stretchX: Float = 1f,
    val stretchY: Float = 1f,
    val selected: Boolean = false,
    /**
     * Only for COMPOUND clips: the clips inside, laid out one after another from 0 (their
     * timelineStartMs is the time inside the compound). The compound's source range is a window
     * into that inner timeline, so split / trim / move work on it like on any other clip.
     */
    val children: List<Clip> = emptyList(),
    /** TEXT clips: the words shown, and the look (a style name the app knows, e.g. "CLASSIC"). */
    val text: String = "",
    val style: String = "",
) {
    val isCompound: Boolean get() = kind == MediaKind.COMPOUND

    /** Images and texts have no source file time: their "source range" is just 0..duration. */
    val hasVirtualSource: Boolean get() = kind == MediaKind.IMAGE || kind == MediaKind.TEXT

    val durationMs: Long get() = sourceEndMs - sourceStartMs
    val timelineEndMs: Long get() = timelineStartMs + durationMs

    /** Maps a timeline time inside this clip to the matching time in the source file. */
    fun timelineToSource(timelineMs: Long): Long =
        (sourceStartMs + (timelineMs - timelineStartMs)).coerceIn(sourceStartMs, sourceEndMs)

    fun contains(timelineMs: Long): Boolean = timelineMs in timelineStartMs until timelineEndMs
}

data class Timeline(val clips: List<Clip> = emptyList()) {
    val main: List<Clip>
        get() = clips.filter { it.track == Track.MAIN }.sortedBy { it.timelineStartMs }

    fun track(track: Track): List<Clip> =
        clips.filter { it.track == track }.sortedBy { it.timelineStartMs }

    /** Project duration is defined by the main track. */
    val durationMs: Long get() = clips.filter { it.track == Track.MAIN }.sumOf { it.durationMs }

    val selected: Clip? get() = clips.firstOrNull { it.selected }

    fun byId(id: String): Clip? = clips.firstOrNull { it.id == id }
}

/** Where a timeline time lands on the main track. */
data class MainPosition(val index: Int, val clip: Clip, val sourceMs: Long)

/** Result of an edit: either a new timeline or the unchanged one plus a reason. */
data class EditResult(val timeline: Timeline, val changed: Boolean, val reason: String? = null)
