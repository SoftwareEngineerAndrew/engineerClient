package com.engineerclient.poi

/**
 * Where each teammate is, as far as the points of interest go, from whichever source knows: their own game over the party
 * link ([Source.SHARED], exact and theirs), their player in view or their shared pose ([Source.SEEN], [Source.POSE]), or a
 * party message ("at ee3!", [Source.CHAT]) from games without this. Names in lower case, times in ms; one thread (the game's).
 *
 * A fresh share wins (their own game, every tick's position); otherwise the newest of the rest, so a callout after they went
 * out of view counts, and one before they were seen elsewhere doesn't. Someone unseen keeps their place, as in Recore. A
 * callout only counts in the section it came in.
 */
class PoiPresence(private val shareFreshMs: Long = SHARE_FRESH_MS) {
    enum class Source(val label: String) { SHARED("shared"), SEEN("seen"), POSE("pose"), CHAT("party chat") }

    /** On spot [poi] (0 none) in section area [area] (see [PoiWire]) since [since], by [source]. */
    class Where(val poi: Int, val area: Int, val since: Long, val source: Source)

    private class Entry {
        var shared: Where? = null; var sharedAt = 0L
        var seen: Where? = null; var seenAt = 0L
        var chat: Where? = null; var chatSection = 0
    }

    private val book = HashMap<String, Entry>()

    /** Their game's own frame: on [poi] in [area] for [heldMs] so far, heard at [now]. */
    fun shared(name: String, poi: Int, area: Int, heldMs: Long, now: Long) {
        val e = book.getOrPut(name) { Entry() }
        val since = now - heldMs
        val was = e.shared
        // The same spot again: its first time stands (a frame's tenths are rounded down, and the link adds a few ms).
        e.shared = if (was != null && was.poi == poi && was.area == area) was else Where(poi, area, since, Source.SHARED)
        e.sharedAt = now
    }

    /** Their position as this game has it now ([Source.SEEN] or [Source.POSE]). */
    fun seen(name: String, poi: Int, area: Int, now: Long, source: Source) {
        val e = book.getOrPut(name) { Entry() }
        val was = e.seen
        e.seen = if (was != null && was.poi == poi && was.area == area) Where(poi, area, was.since, source) else Where(poi, area, now, source)
        e.seenAt = now
    }

    /** A party message saying they just got to [poi], in [section]. */
    fun callout(name: String, poi: Int, now: Long, section: Int) {
        val e = book.getOrPut(name) { Entry() }
        val p = PointsOfInterest.byId(poi) ?: return
        e.chat = Where(poi, p.opens, now, Source.CHAT)
        e.chatSection = section
    }

    /** Dead (or gone): whatever was known about them no longer holds. */
    fun forget(name: String) { book.remove(name) }

    fun clear() = book.clear()

    /** Where [name] is at [now], in [section] (for the callouts), as best known; null when nothing is. */
    fun where(name: String, now: Long, section: Int): Where? {
        val e = book[name] ?: return null
        val shared = e.shared
        if (shared != null && now - e.sharedAt <= shareFreshMs) return shared.earliest(e)
        val chat = e.chat?.takeIf { e.chatSection == section }
        val best = listOfNotNull(shared?.let { it to e.sharedAt }, e.seen?.let { it to e.seenAt }, chat?.let { it to it.since })
            .maxByOrNull { it.second }?.first ?: return null
        return best.earliest(e)
    }

    /** This answer, with the earliest time any source had them on the same spot (each source sees the arrival at its own moment). */
    private fun Where.earliest(e: Entry): Where {
        val same = listOfNotNull(e.shared, e.seen, e.chat).filter { it.poi == poi && it.area == area }
        val first = same.minOfOrNull { it.since } ?: since
        return if (first < since) Where(poi, area, first, source) else this
    }

    companion object {
        /** A shared frame older than this is stale: they're sent once a second, so this is two missed. */
        const val SHARE_FRESH_MS = 2_500L
    }
}
