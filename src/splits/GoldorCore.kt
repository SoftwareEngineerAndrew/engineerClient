package com.engineerclient.splits

import java.util.Locale

/**
 * The Goldor sub split: who got into the core and when, and Goldor setting off, in server ticks
 * from "The Core entrance is opening!".
 *
 * Watched from S4's start: from then on nobody can leave the inner chamber (the core box,
 * z >= 54 - Hypixel snaps you back), so the first time a player is seen in it is their way in
 * for good, even before the core opens (standing at its door). Goldor waits until everyone alive
 * is in, then sets off (in 35 recorded F7 runs: 0-2 ticks after the last one in, up to 7), and
 * from [GRACE] ticks after that nobody new counts. A time is exact when the player was in view
 * the tick before they came in; someone already in when the watch started has no time (they were
 * in before S4).
 * Kept free of Minecraft (the module feeds it), so it tests headlessly.
 */
class GoldorCore {
    /** [tick]: when they came in (server ticks); [exact]: seen the tick before; [beforeS4]: in when the watch started. */
    class Entry(val tick: Int, val exact: Boolean, val beforeS4: Boolean)

    /** The server tick the watch started on (S4's start, or the core opening if S4's wasn't seen). */
    var startTick: Int? = null; private set
    var openTick: Int? = null; private set
    /** Goldor setting off, server ticks after the core opened. */
    var goldorMoved: Int? = null; private set
    var pasted = false
    private val inside = LinkedHashMap<String, Entry>()
    /** The last look ([round]) each player was in view in: a look is a client tick, so server lag can't fake a gap. */
    private val seen = HashMap<String, Int>()
    private var round = 0
    /** The server tick it stops showing on (Necron's first line + [LINGER]). */
    private var hideAt: Int? = null

    /** The server tick of the latest [look]. */
    private var now = 0

    /** Watching who comes in: from the start until [GRACE] ticks after Goldor sets off. */
    val watching get() = startTick != null && hideAt == null && goldorMoved.let { g -> g == null || now < openTick!! + g + GRACE }
    /** Showing (from the start until [LINGER] into Necron). */
    val active get() = startTick != null && hideAt == null

    fun reset() { startTick = null; openTick = null; goldorMoved = null; pasted = false; inside.clear(); seen.clear(); round = 0; hideAt = null }

    /** S4 started on [tick]. */
    fun onS4(tick: Int) { if (startTick == null) startTick = tick }

    fun onCoreOpen(tick: Int) {
        if (openTick != null) return
        if (startTick == null) startTick = tick
        openTick = tick
    }

    /** A new look at everyone, on server tick [tick]: each client tick, before its [observe]s. True while [watching]. */
    fun look(tick: Int): Boolean {
        now = tick
        if (!watching) return false
        round++
        return true
    }

    /**
     * Player [name] at this look, on server tick [tick]: [inCore] true or false where they are,
     * null when out of view. The first look in counts; once in, nothing takes them out.
     */
    fun observe(name: String, inCore: Boolean?, tick: Int) {
        if (!watching || inCore == null) return
        val before = seen.put(name, round)
        if (!inCore || name in inside) return
        // In at the first look: they were in before the watch started.
        val first = round == 1
        inside[name] = Entry(tick, exact = first || before == round - 1, beforeS4 = first)
    }

    fun onGoldorMoved(tick: Int) {
        val open = openTick ?: return
        if (goldorMoved == null) goldorMoved = tick - open
    }

    fun onNecron(tick: Int) { if (startTick != null && hideAt == null) hideAt = tick + LINGER }

    fun entry(name: String): Entry? = inside[name]

    /** The last of [alive] in and their entry, once they all are; null before (or with nobody). */
    fun lastIn(alive: Collection<String>): Pair<String, Entry>? {
        if (alive.isEmpty() || alive.any { it !in inside }) return null
        return alive.map { it to inside.getValue(it) }.maxByOrNull { it.second.tick }
    }

    /** Everyone of [alive] in on [lastIn]'s tick (a leap together can tie). */
    private fun lastNames(alive: Collection<String>): List<String> {
        val t = lastIn(alive)?.second?.tick ?: return emptyList()
        return alive.filter { inside.getValue(it).tick == t }
    }

    /** An entry's time from the core opening ("in" while it isn't open, ~ for a guess). */
    private fun time(e: Entry): String {
        val open = openTick
        return when {
            e.beforeS4 -> "already in"
            open == null -> "in"
            else -> (if (e.exact) "" else "~") + secs(e.tick - open)
        }
    }

    /**
     * The HUD's lines at [now] (server ticks), in the order they came in, [me] in white: times
     * green, before the opening teal, the last of [alive] gold once they all are, a guess (not in
     * view the tick before) grey with ~; who of [alive] isn't in yet, with "...".
     */
    fun lines(now: Int, me: String?, alive: Collection<String>): List<String> {
        if (startTick == null || hideAt?.let { now >= it } == true) return emptyList()
        val open = openTick
        val out = mutableListOf(if (open == null) "§eCore §8(S4)" else "§eCore")
        val last = lastIn(alive)
        val lastNames = lastNames(alive)
        for ((name, e) in inside) {
            val colour = when {
                name in lastNames -> "§6"
                !e.exact -> "§8"
                open == null || e.tick < open -> "§3"
                else -> "§a"
            }
            out += "${if (name == me) "§f" else "§7"}$name $colour${time(e)}"
        }
        for (name in alive) if (name !in inside) out += "${if (name == me) "§f" else "§7"}$name §8..."
        if (open != null) {
            val gap = goldorMoved?.let { g -> last?.let { " §8(" + signed(open + g - it.second.tick) + " after the last in)" } }.orEmpty()
            out += "§eGoldor moved " + (goldorMoved?.let { "§f" + secs(it) + gap } ?: "§8...")
        }
        return out
    }

    /**
     * Paste Last In Core's line: once all of [alive] (you and your four teammates) are in, the last
     * one and their time; null while someone is still out, when any time is a guess, or when the
     * last one was in before S4 (no time).
     */
    fun pasteLine(alive: Collection<String>): String? {
        val open = openTick ?: return null
        val e = lastIn(alive)?.second ?: return null
        if (alive.any { !inside.getValue(it).exact } || e.beforeS4) return null
        val t = e.tick - open
        return "Last in core: ${lastNames(alive).joinToString(" & ")} ${secs(t)}" + if (t < 0) " (before the core opened)" else ""
    }

    companion object {
        const val LINGER = 200
        /**
         * Server ticks still watched after Goldor sets off: his setting off is seen from his
         * position, which can come in a tick or two before the last one's (in 35 recorded runs, up
         * to 2 ticks before).
         */
        const val GRACE = 10
        fun secs(ticks: Int): String = String.format(Locale.ROOT, "%.2fs", ticks / 20.0)
        private fun signed(ticks: Int) = (if (ticks >= 0) "+" else "-") + secs(Math.abs(ticks))
    }
}
