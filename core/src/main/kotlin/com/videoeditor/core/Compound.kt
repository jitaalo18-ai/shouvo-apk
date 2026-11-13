package com.videoeditor.core

import kotlin.math.max
import kotlin.math.min

/**
 * Compound clips: several clips handled as one block (like CapCut's "compound clip").
 *
 * A compound is not a new media file. Its clips keep playing exactly as before - they only share
 * a [Clip.groupId]. Rules that every edit keeps:
 *  - the main-track members of a compound sit next to each other (one block on the timeline);
 *  - free-track members (overlay / sound effects / music) move together with the compound;
 *  - a compound without main-track members does not exist.
 * Preview and export therefore need no special code, and "Break apart" restores plain clips.
 */
object Compound {

    fun members(t: Timeline, gid: String): List<Clip> = t.clips.filter { it.groupId == gid }

    fun mainMembers(t: Timeline, gid: String): List<Clip> = t.main.filter { it.groupId == gid }

    fun freeMembers(t: Timeline, gid: String): List<Clip> =
        t.clips.filter { it.groupId == gid && it.track != Track.MAIN }

    /** Where the compound is on the timeline (its main-track part), or null. */
    fun span(t: Timeline, gid: String): MsRange? {
        val m = mainMembers(t, gid)
        if (m.isEmpty()) return null
        return MsRange(m.first().timelineStartMs, m.last().timelineEndMs)
    }

    /** The main track cut into blocks: each compound is one block, every other clip its own. */
    fun units(main: List<Clip>): List<List<Clip>> {
        val out = ArrayList<List<Clip>>()
        var i = 0
        while (i < main.size) {
            val g = main[i].groupId
            var j = i
            if (g != null) while (j + 1 < main.size && main[j + 1].groupId == g) j++
            out.add(main.subList(i, j + 1).toList())
            i = j + 1
        }
        return out
    }

    /**
     * Combines clips into one compound. [ids] may be any clips; on the main track everything from
     * the first to the last chosen clip is included (a compound has no holes), and compounds that
     * are touched are taken in whole. Chosen overlay / sound / music clips join as well.
     */
    fun make(t: Timeline, ids: Set<String>, newGroupId: String): EditResult {
        val main = t.main
        val picked = main.indices.filter { main[it].id in ids }
        if (picked.isEmpty()) return EditResult(t, false, "Select at least one clip on the main video track.")
        var from = picked.min()
        var to = picked.max()
        main[from].groupId?.let { g -> while (from > 0 && main[from - 1].groupId == g) from-- }
        main[to].groupId?.let { g -> while (to < main.lastIndex && main[to + 1].groupId == g) to++ }
        val absorbed = (from..to).mapNotNull { main[it].groupId }.toSet()
        val mainIds = (from..to).map { main[it].id }.toSet()
        val freeIds = t.clips.filter { it.track != Track.MAIN && (it.id in ids || it.groupId in absorbed) }.map { it.id }.toSet()
        val oneExisting = absorbed.size == 1 && mainIds.all { main.first { c -> c.id == it }.groupId != null } &&
            freeIds.all { id -> t.byId(id)?.groupId != null }
        if (oneExisting) return EditResult(t, false, "This is already one compound clip.")
        val out = t.clips.map { c ->
            if (c.id in mainIds || c.id in freeIds) c.copy(groupId = newGroupId) else c
        }
        return EditResult(repair(Timeline(out)), true)
    }

    /** Turns the compound back into separate clips. Nothing else changes. */
    fun breakApart(t: Timeline, gid: String): Timeline =
        Timeline(t.clips.map { if (it.groupId == gid) it.copy(groupId = null) else it })

    /** Deletes the whole compound (all its clips). */
    fun remove(t: Timeline, gid: String): EditResult {
        if (t.main.all { it.groupId == gid }) {
            return EditResult(t, false, "A project needs at least one clip on the main track.")
        }
        val rest = t.clips.filter { it.groupId != gid }
        return EditResult(
            TimelineOps.relayFrom(t, rest.filter { it.track == Track.MAIN }.sortedBy { it.timelineStartMs }, rest.filter { it.track != Track.MAIN }),
            true,
        )
    }

    /** Puts a copy of the compound right after it (a new compound) and selects the copy. */
    fun duplicate(t: Timeline, gid: String, newId: () -> String, newGroupId: String): Timeline {
        val mm = mainMembers(t, gid)
        if (mm.isEmpty()) return t
        val length = mm.sumOf { it.durationMs }
        val copies = mm.map { it.copy(id = newId(), groupId = newGroupId, selected = false) }
        val main = t.main.toMutableList()
        main.addAll(main.indexOfLast { it.groupId == gid } + 1, copies)
        var out = TimelineOps.relayFrom(t, main, TimelineOps.others(t))
        for (f in freeMembers(t, gid).sortedBy { it.layer }) {
            out = TimelineOps.addClip(out, f.copy(id = newId(), groupId = newGroupId, selected = false, timelineStartMs = f.timelineStartMs + length))
        }
        return TimelineOps.select(out, copies.first().id)
    }

    /** Moves the whole compound one block earlier (-1) or later (+1) on the main track. */
    fun move(t: Timeline, gid: String, delta: Int): Timeline {
        val units = units(t.main).toMutableList()
        val from = units.indexOfFirst { it.first().groupId == gid }
        if (from < 0) return t
        val to = (from + delta).coerceIn(0, units.lastIndex)
        if (to == from) return t
        units.add(to, units.removeAt(from))
        return TimelineOps.relayFrom(t, units.flatten(), TimelineOps.others(t))
    }

    /**
     * Splits the compound at [timelineMs] into two compounds. The clip under that time is cut
     * (or, near a clip edge, the cut goes exactly on the edge). The right part gets [newGroupId].
     */
    fun split(t: Timeline, gid: String, timelineMs: Long, newId: () -> String, newGroupId: String): EditResult {
        val range = span(t, gid) ?: return EditResult(t, false, "Clip not found")
        if (timelineMs < range.startMs + TimelineOps.MIN_SPLIT_MS || timelineMs > range.endMs - TimelineOps.MIN_SPLIT_MS) {
            return EditResult(t, false, "Move the playhead inside the compound clip to split it")
        }
        val member = mainMembers(t, gid).first { timelineMs < it.timelineEndMs }
        var out = t
        val boundary: Long
        if (timelineMs > member.timelineStartMs + TimelineOps.MIN_SPLIT_MS && timelineMs < member.timelineEndMs - TimelineOps.MIN_SPLIT_MS) {
            val r = TimelineOps.split(t, member.id, timelineMs, newId)
            if (!r.changed) return r
            out = r.timeline
            boundary = timelineMs
        } else {
            boundary = if (timelineMs - member.timelineStartMs < member.timelineEndMs - timelineMs) member.timelineStartMs else member.timelineEndMs
        }
        val moved = out.clips.map {
            if (it.groupId == gid && it.timelineStartMs >= boundary) it.copy(groupId = newGroupId) else it
        }
        return EditResult(repair(Timeline(moved)), true)
    }

    /**
     * Trims the compound from [base] (the timeline when the drag started): [cutStartMs] > 0 hides
     * that much from its beginning, < 0 brings hidden material back; the same for [cutEndMs] at its
     * end. Clips that are hidden completely leave the compound while hidden and come back when the
     * handle is dragged back out (the result is always computed from [base]).
     */
    fun trim(base: Timeline, gid: String, cutStartMs: Long, cutEndMs: Long): Timeline {
        val main = base.main
        val first = main.indexOfFirst { it.groupId == gid }
        if (first < 0) return base
        val last = main.indexOfLast { it.groupId == gid }
        val members = main.subList(first, last + 1).toMutableList()
        val total = members.sumOf { it.durationMs }
        val minTotal = TimelineOps.MIN_TRIM_MS
        var cs = cutStartMs
        var ce = cutEndMs
        if (cs > 0) cs = min(cs, total - minTotal - max(ce, 0L)).coerceAtLeast(0)
        if (ce > 0) ce = min(ce, total - minTotal - max(cs, 0L)).coerceAtLeast(0)

        // beginning
        if (cs > 0) {
            var left = cs
            while (left > 0 && members.isNotEmpty()) {
                val f = members.first()
                if (members.size > 1 && left > f.durationMs - TimelineOps.MIN_SPLIT_MS) {
                    members.removeAt(0)
                    left -= f.durationMs
                } else {
                    val keep = (f.durationMs - left).coerceAtLeast(TimelineOps.MIN_SPLIT_MS)
                    members[0] = if (f.kind == MediaKind.IMAGE) f.copy(sourceStartMs = 0, sourceEndMs = keep)
                    else f.copy(sourceStartMs = f.sourceEndMs - keep)
                    left = 0
                }
            }
        } else if (cs < 0) {
            val f = members.first()
            members[0] = if (f.kind == MediaKind.IMAGE) f.copy(sourceStartMs = 0, sourceEndMs = min(f.sourceEndMs - cs, TimelineOps.MAX_IMAGE_MS))
            else f.copy(sourceStartMs = (f.sourceStartMs + cs).coerceAtLeast(0))
        }
        // end
        if (ce > 0) {
            var left = ce
            while (left > 0 && members.isNotEmpty()) {
                val l = members.last()
                if (members.size > 1 && left > l.durationMs - TimelineOps.MIN_SPLIT_MS) {
                    members.removeAt(members.lastIndex)
                    left -= l.durationMs
                } else {
                    val keep = (l.durationMs - left).coerceAtLeast(TimelineOps.MIN_SPLIT_MS)
                    members[members.lastIndex] = l.copy(sourceEndMs = l.sourceStartMs + keep)
                    left = 0
                }
            }
        } else if (ce < 0) {
            val l = members.last()
            members[members.lastIndex] = if (l.kind == MediaKind.IMAGE) l.copy(sourceEndMs = min(l.sourceEndMs - ce, TimelineOps.MAX_IMAGE_MS))
            else l.copy(sourceEndMs = min(l.sourceEndMs - ce, max(l.sourceDurationMs, l.sourceEndMs)))
        }
        val newMain = main.subList(0, first) + members + main.subList(last + 1, main.size)
        return TimelineOps.relayFrom(base, newMain, TimelineOps.others(base))
    }

    /** Timeline time of the content start of a clip ("where source time 0 would be"). */
    private fun anchor(c: Clip): Long = c.timelineStartMs - c.sourceStartMs

    /**
     * After the main track changed from [before] to [after]: free-track members of each compound
     * move by as much as the compound's content moved, so they stay on the same moment.
     */
    fun follow(before: Timeline, after: Timeline): Timeline {
        val groups = after.clips.mapNotNullTo(HashSet()) { if (it.track != Track.MAIN) it.groupId else null }
        if (groups.isEmpty()) return after
        val old = before.clips.associateBy { it.id }
        val main = after.main
        val shift = HashMap<String, Long>()
        for (g in groups) {
            val firstNow = main.firstOrNull { it.groupId == g } ?: continue
            val then = old[firstNow.id] ?: continue
            if (then.track != Track.MAIN) continue
            val d = anchor(firstNow) - anchor(then)
            if (d != 0L) shift[g] = d
        }
        if (shift.isEmpty()) return after
        return Timeline(after.clips.map { c ->
            val d = if (c.track != Track.MAIN) c.groupId?.let { shift[it] } else null
            if (d == null || old[c.id] == null) c else c.copy(timelineStartMs = (c.timelineStartMs + d).coerceAtLeast(0))
        })
    }

    /**
     * Keeps the rules: if the main-track clips of a compound are no longer next to each other only
     * the longest run stays in it, and a compound without main-track clips is dissolved.
     */
    fun repair(t: Timeline): Timeline {
        if (t.clips.none { it.groupId != null }) return t
        val main = t.main
        val keep = HashMap<String, IntRange>()
        var i = 0
        while (i < main.size) {
            val g = main[i].groupId
            if (g == null) {
                i++
                continue
            }
            var j = i
            while (j + 1 < main.size && main[j + 1].groupId == g) j++
            val run = i..j
            val prev = keep[g]
            if (prev == null || run.count() > prev.count()) keep[g] = run
            i = j + 1
        }
        val clear = HashSet<String>()
        main.forEachIndexed { idx, c ->
            val g = c.groupId ?: return@forEachIndexed
            if (idx !in keep.getValue(g)) clear += c.id
        }
        for (c in t.clips) if (c.track != Track.MAIN && c.groupId != null && c.groupId !in keep) clear += c.id
        if (clear.isEmpty()) return t
        return Timeline(t.clips.map { if (it.id in clear) it.copy(groupId = null) else it })
    }
}
