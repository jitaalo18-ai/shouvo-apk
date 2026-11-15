package com.videoeditor.core

/** One source-relative interval per whitespace-delimited word. Empty data means static text. */
object CaptionTiming {
    fun encode(words: List<MsRange>): String = words.joinToString(";") { "${it.startMs},${it.endMs}" }
    fun decode(value: String): List<MsRange> = if (value.isBlank()) emptyList() else try {
        value.split(';').map {
            val p = it.split(',')
            require(p.size == 2)
            MsRange(p[0].toLong(), p[1].toLong()).also { r -> require(r.endMs >= r.startMs) }
        }
    } catch (_: IllegalArgumentException) { emptyList() }
    fun shift(value: String, delta: Long): String = encode(decode(value).map { MsRange(it.startMs + delta, it.endMs + delta) })
    fun active(clip: Clip, timelineMs: Long): Int {
        if (!clip.contains(timelineMs)) return -1
        val time = clip.timelineToSource(timelineMs)
        return decode(clip.captionTiming).indexOfFirst { time >= it.startMs && time < it.endMs }
    }
}
