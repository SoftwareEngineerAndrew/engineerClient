package com.engineerclient.splits

import java.util.Locale

/**
 * Dungeon splits: which chat line starts and ends each timed section of a run, worked out as chat
 * arrives.
 *
 * The sections, their names and the time format are the ones Devonian shows, so a run timed here
 * reads the same as a run timed there and the team can compare times. It is written from that
 * behaviour — the lines Hypixel sends and what each one starts or stops — not from Devonian's code,
 * which is GPL while this mod is not.
 *
 * Nothing here touches Minecraft, so it tests headlessly: feed it chat lines with the clock
 * readings they arrived at, then ask for the splits.
 */

/** The two clocks a split is measured on: real time, and the server's own tick count. */
data class Stamp(val realMs: Long, val tick: Int)

/**
 * One timed section. [stop] is null while it is still running — the HUD counts it up to now.
 * [long] picks the plain-seconds format.
 */
data class Split(val label: String, val long: Boolean, val start: Stamp, val stop: Stamp?)

/**
 * Something that happened inside a split: a terminal done, a death, a blessing. [at] is when, so
 * a sub-split HUD can show it as an offset from the split it belongs to.
 */
data class SubSplit(val label: String, val at: Stamp, val raw: Boolean = false)

/** Which clock a split line shows. */
enum class SplitClock { REAL, TICKS, BOTH }

object SplitFormat {

    /**
     * Devonian's time format: "long" splits as plain seconds, the rest as 9.35s / 1m 27.01s /
     * 1h 02m 03.45s. The fraction is worked out on its own and never carries into the seconds, so
     * 12.996s reads as 12.00s — Devonian does that too, and matching it matters more than being
     * right, because the times get compared.
     */
    fun time(ms: Long, long: Boolean): String {
        if (long) return String.format(Locale.ROOT, "%.2f", ms / 1000.0) + "s"
        if (ms < 0) return "-" + time(-ms, false)
        val frac = String.format(Locale.ROOT, "%.2f", ms % 1000 / 1000.0).substring(1)
        val s = ms / 1000
        val m = s / 60
        val h = m / 60
        val ss = (s % 60).toString().padStart(2, '0')
        return when {
            s < 60 -> "$s${frac}s"
            m < 60 -> "${m}m $ss${frac}s"
            else -> "${h}h ${(m % 60).toString().padStart(2, '0')}m $ss${frac}s"
        }
    }

    /** One HUD line: the section's name, then how long it took (or has taken so far). */
    fun line(split: Split, now: Stamp, clock: SplitClock): String {
        val stop = split.stop ?: now
        val label = split.label.replace('&', '§')
        val real = time(stop.realMs - split.start.realMs, split.long)
        val ticks = time((stop.tick - split.start.tick) * 50L, split.long)
        val time = when (clock) {
            SplitClock.REAL -> "§a$real"
            SplitClock.TICKS -> "§b$ticks"
            SplitClock.BOTH -> "§a$real §7(§b$ticks§7)"
        }
        return "$label§r§f: $time"
    }
}

/**
 * The state of one run's splits. Feed every chat line to [onChat] (colour codes stripped) and read
 * the three HUDs' splits back. One tracker per run: [reset] on world load.
 */
class SplitTracker {

    private var mort: Stamp? = null          // Mort hands you the map: the clear starts
    private var blood: Stamp? = null         // the blood door opens / the Watcher speaks
    private var proven: Stamp? = null        // the Watcher lets you pass
    private var end: Stamp? = null           // EXTRA STATS: the run is over
    private var floorNo: Int? = null
    private var floorStart: Stamp? = null
    private var bossStarts: Array<Stamp?> = emptyArray()

    /**
     * What happened inside each split, by split label. Every dungeon event goes to whichever split
     * was running when it arrived; which of them are worth keeping is the thing we are collecting
     * this data to find out, so nothing is filtered here beyond [SplitEvents] recognising the line
     * as an event at all.
     */
    private val subSplits = linkedMapOf<String, MutableList<SubSplit>>()

    fun reset() {
        mort = null; blood = null; proven = null; end = null
        floorNo = null; floorStart = null; bossStarts = emptyArray()
        subSplits.clear()
    }

    fun onChat(msg: String, at: Stamp) {
        // The windows below are the ones this line was sent in, so a line that closes a window (the
        // boss's first words, EXTRA STATS) still counts inside it.
        val closed = clearEnd()
        val ended = end
        val openBefore = openLabels()

        if (mort == null && msg == MORT) mort = at
        if (end == null && EXTRA_STATS.matches(msg)) end = at

        if (floorStart == null) {
            for ((no, floor) in FLOORS) if (msg == floor.start) {
                floorNo = no
                floorStart = at
                bossStarts = arrayOfNulls(floor.splits.size)
                bossStarts[0] = at
                break
            }
        }

        // The clear: the blood door, then the Watcher's blessing. Both are "[BOSS] The Watcher:"
        // lines, so the first opens blood and the later one falls through to proven.
        if (mort != null && closed == null) {
            when {
                blood == null && BLOOD_OPEN.matches(msg) -> blood = at
                blood != null && proven == null && msg == WATCHER_END -> proven = at
            }
        }

        // The boss: each split starts on its own line and runs until the next one starts.
        val floor = FLOORS[floorNo]
        if (floor != null && ended == null) {
            floor.splits.forEachIndexed { i, split ->
                if (i == 0 || bossStarts[i] != null) return@forEachIndexed
                if (split.starts?.invoke(msg) != true) return@forEachIndexed
                bossStarts[i] = at
            }
        }

        // Anything notable in the line is filed under every split that was open when it arrived.
        // Splits nest — Boss Entry spans the whole clear, Boss spans every phase — so an event
        // belongs to all of them, not just the innermost; that is what makes a HUD like "Boss Entry
        // Sub Splits" worth having. Open *before* this line, so the message that ends a split is
        // still counted inside it rather than opening the next one's list.
        SplitEvents.label(msg)?.let { event ->
            val owners = openBefore.ifEmpty { openLabels() }
            for (owner in owners) subSplits.getOrPut(owner) { mutableListOf() } += SubSplit(event, at)
        }
    }

    /** The splits being timed right now: started, not yet ended. */
    private fun openLabels(): List<String> = splits().filter { it.stop == null }.map { it.label }

    /**
     * Every split of the run in the order it happened: the clear, then the boss's phases. The clear
     * splits used to vanish the moment the boss started — they stay now, frozen at their final
     * times, so one HUD carries the whole run.
     */
    fun splits(): List<Split> {
        val out = mutableListOf<Split>()
        val mort = mort
        if (mort != null) {
            val closed = clearEnd()
            out += Split(BLOOD, true, mort, blood ?: closed)
            blood?.let { out += Split(WATCHER, true, it, proven ?: closed) }
            proven?.let { out += Split(PORTAL, true, it, closed) }
        }
        val floorStart = floorStart
        val floor = FLOORS[floorNo]
        if (floorStart != null && floor != null) {
            floor.splits.forEachIndexed { i, split ->
                val start = (if (i == 0) floorStart else bossStarts[i]) ?: return@forEachIndexed
                val next = (i + 1 until floor.splits.size).firstNotNullOfOrNull { bossStarts[it] }
                out += Split(split.label, split.long, start, next ?: end)
            }
        }
        return out
    }

    /** What happened inside the split with this label, oldest first. */
    fun subSplits(label: String): List<SubSplit> = subSplits[label].orEmpty()

    /** When the clear stopped being the thing you're timing: the boss starting, or the run ending. */
    private fun clearEnd(): Stamp? = listOfNotNull(floorStart, end).minByOrNull { it.realMs }

    private class BossSplit(val label: String, val long: Boolean = false, val starts: ((String) -> Boolean)? = null)
    private class FloorSplits(val start: String, val splits: List<BossSplit>)

    companion object {
        const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
        const val WATCHER_END = "[BOSS] The Watcher: You have proven yourself. You may pass."
        const val TERMINALS = "&6Terminals"
        const val GOLDOR = "&8Goldor"
        const val BLOOD = "&4Blood Rush"
        const val WATCHER = "&cWatcher"
        const val PORTAL = "&dPortal"

        val BLOOD_OPEN = Regex("^(\\[BOSS] The Watcher: .+?|The BLOOD DOOR has been opened!)$")
        val EXTRA_STATS = Regex("^ +> EXTRA STATS <$")
        // Terminals starts on the first one done, or on Goldor's greeting if the team is that fast.
        val TERMINALS_START = Regex("^(?:\\w+ (?:activated|completed) a (?:terminal|lever|device)! \\(\\d/\\d\\)|\\[BOSS] Goldor: Who dares trespass into my domain\\?)$")
        /**
         * The floor, the line its boss starts on, and its phases (the first starts with the boss).
         *
         * F7 only, which is M7 as well — this team runs nothing else, all 32 recorded runs are F7,
         * and the floors 1-6 that used to sit here were lines nobody could check against real chat.
         * Another floor goes back in when there is a recording of it to write it from.
         */
        private val FLOORS: Map<Int, FloorSplits> = mapOf(
            7 to FloorSplits("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", listOf(
                BossSplit("&5Maxor"),
                BossSplit("&9Storm", long = true) { it == "[BOSS] Storm: Pathetic Maxor, just like expected." },
                BossSplit(TERMINALS) { TERMINALS_START.matches(it) },
                BossSplit(GOLDOR) { it == "The Core entrance is opening!" },
                BossSplit("&4Necron") { it == "[BOSS] Necron: You went further than any human before, congratulations." },
            )),
        )

        /**
         * Every label a run can produce: the clear first, then the boss's phases.
         * The module makes one sub-split HUD per entry up front, because a HUD has to exist before
         * the run that would fill it — most of them stay empty on any given floor.
         */
        val ALL_LABELS: List<String> = buildList {
            add(BLOOD); add(WATCHER); add(PORTAL)
            for (floor in FLOORS.values) for (split in floor.splits) add(split.label)
        }.distinct()
    }
}