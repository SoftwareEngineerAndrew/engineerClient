package com.engineerclient.poi

import java.util.EnumMap

/**
 * Whether each teammate is surely on a point of interest right now. A wrong highlight sends you leaping to someone who isn't
 * where it says, which is worse than no highlight, so this only answers when it's sure:
 *  - only readings that are current count: their own game's frame over the party link ([Source.SHARED], at most
 *    [shareFreshMs] old), their player in view this tick ([Source.SEEN]) or their shared pose this tick ([Source.POSE]).
 *    Someone out of view with no fresh frame is unknown, never "where they were last".
 *  - every current reading has to say the same spot; any that says otherwise (they've stepped off, or are in a leap's drag)
 *    and it's no.
 *  - they've been on it, by all of them, for at least [settleMs], so a pass across a box's edge doesn't flash.
 * Names in lower case, times in ms; one thread (the game's).
 */
class PoiPresence(
    private val shareFreshMs: Long = SHARE_FRESH_MS,
    private val sightFreshMs: Long = SIGHT_FRESH_MS,
    private val settleMs: Long = SETTLE_MS,
) {
    enum class Source(val label: String) { SHARED("shared"), SEEN("seen"), POSE("pose") }

    /**
     * Surely on [poi]: there since [since] (the first any of [sources] had them there, for who got there first), sure of it
     * from [sureFrom] (every current source had had them there for [settleMs]: when the highlight shows).
     */
    class Where(val poi: Int, val since: Long, val sureFrom: Long, val sources: Set<Source>)

    /** One source's latest: on [poi] (0 none) since [since], last heard [at]. */
    private class Reading(val poi: Int, val since: Long, val at: Long)

    private class Entry {
        val readings = EnumMap<Source, Reading>(Source::class.java)
        var seq = -1
    }

    private val book = HashMap<String, Entry>()

    private fun freshFor(s: Source) = if (s == Source.SHARED) shareFreshMs else sightFreshMs

    /**
     * [s] has them on [poi] since [since]. The same spot, still current: its first time stands. After a gap (out of view,
     * frames lost) it starts again, as nothing says they stayed; so does a frame whose own time on it ([since] from their
     * game) is clearly later than the one kept (they stepped off and back on between frames).
     */
    private fun put(e: Entry, s: Source, poi: Int, since: Long, now: Long, ownTime: Boolean = false) {
        val was = e.readings[s]
        val keep = was != null && was.poi == poi && now - was.at <= freshFor(s) && (!ownTime || since - was.since < SHARE_JITTER_MS)
        e.readings[s] = Reading(poi, if (keep) minOf(was.since, since) else since, now)
    }

    /**
     * Their game's own frame number [seq]: on [poi] for [heldMs] so far, heard at [now]. One older than the newest current
     * frame came out of order and is dropped. False if it was.
     */
    fun shared(name: String, seq: Int, poi: Int, heldMs: Long, now: Long): Boolean {
        val e = book.getOrPut(name) { Entry() }
        val last = e.readings[Source.SHARED]
        if (e.seq >= 0 && last != null && now - last.at <= shareFreshMs && !PoiWire.after(seq, e.seq)) return false
        e.seq = seq
        // Their time on it tops out at 25.5 s: past that it says nothing about when they got there.
        put(e, Source.SHARED, poi, now - heldMs, now, ownTime = heldMs < PoiWire.MAX_HELD_MS)
        return true
    }

    /** Their position as this game has it this tick ([Source.SEEN] or [Source.POSE]): on [poi], 0 for none or moving. */
    fun seen(name: String, poi: Int, now: Long, source: Source) {
        require(source != Source.SHARED)
        put(book.getOrPut(name) { Entry() }, source, poi, now, now)
    }

    /** Dead (or gone): whatever was known about them no longer holds. */
    fun forget(name: String) { book.remove(name) }

    fun clear() = book.clear()

    /** Each source's current reading for [name] (spot id, 0 none), for the log. */
    fun current(name: String, now: Long): Map<Source, Int> =
        book[name]?.readings?.filter { (s, r) -> now - r.at <= freshFor(s) }?.mapValues { it.value.poi } ?: emptyMap()

    /** The spot [name] is surely on at [now], or null: not known, not on one, sources disagreeing or not settled yet. */
    fun sureAt(name: String, now: Long): Where? {
        val cur = book[name]?.readings?.filter { (s, r) -> now - r.at <= freshFor(s) } ?: return null
        val poi = cur.values.firstOrNull()?.poi ?: return null
        if (poi == 0 || cur.values.any { it.poi != poi }) return null
        val sureFrom = cur.values.maxOf { it.since } + settleMs
        if (now < sureFrom) return null
        return Where(poi, cur.values.minOf { it.since }, sureFrom, cur.keys.toSet())
    }

    companion object {
        /** Frames come every 250 ms: this is one lost, and the link's jitter. */
        const val SHARE_FRESH_MS = 700L
        /** Seen and pose readings are taken every tick (50 ms): a couple missed and they're no longer current. */
        const val SIGHT_FRESH_MS = 150L
        /** On the spot, by every current source, at least this long. */
        const val SETTLE_MS = 250L
        /** A frame's time on its spot is in tenths, rounded down, and the link adds its jitter: no more than this between frames. */
        private const val SHARE_JITTER_MS = 200L
    }
}
