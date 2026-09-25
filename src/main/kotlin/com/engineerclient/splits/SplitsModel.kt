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
 * [long] picks the plain-seconds format, [sub] marks a terminal section (S1-S4) so it can be
 * indented under Terminals.
 */
data class Split(val label: String, val long: Boolean, val start: Stamp, val stop: Stamp?, val sub: Boolean = false)

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
    private var dialogEnd: Stamp? = null
    private var proven: Stamp? = null        // the Watcher lets you pass
    private var watcherMove: Stamp? = null   // the Watcher itself starts moving (fed by the module)
    private var end: Stamp? = null           // EXTRA STATS: the run is over
    private var floorNo: Int? = null
    private var floorStart: Stamp? = null
    private var bossStarts: Array<Stamp?> = emptyArray()
    private var sections: List<Section>? = null

    /** Master mode, for the splits only master runs have. The module sets it from the floor. */
    var master: Boolean = false

    /** When the Watcher finished speaking — the module only watches it for movement after this. */
    val dialogueEnd: Stamp? get() = dialogEnd

    /** Whether the Watcher has already been seen moving (or the fight is over). */
    val watcherMoved: Boolean get() = watcherMove != null || proven != null || blood == null

    fun reset() {
        mort = null; blood = null; dialogEnd = null; proven = null; watcherMove = null; end = null
        floorNo = null; floorStart = null; bossStarts = emptyArray(); sections = null
    }

    /** The Watcher's first movement after its dialogue (the module watches the entity for this). */
    fun onWatcherMove(at: Stamp) {
        if (watcherMove == null && blood != null && proven == null) watcherMove = at
    }

    fun onChat(msg: String, at: Stamp) {
        // The windows below are the ones this line was sent in, so a line that closes a window (the
        // boss's first words, EXTRA STATS) still counts inside it.
        val closed = clearEnd()
        val ended = end
        val goldorWas = goldorStart()

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

        // The clear: the blood door, then the Watcher's dialogue and its blessing. Every one of
        // these is a "[BOSS] The Watcher:" line, so the first opens blood and the rest fall through.
        if (mort != null && closed == null) {
            when {
                blood == null && BLOOD_OPEN.matches(msg) -> blood = at
                blood != null && dialogEnd == null && msg == WATCHER_DIALOG_END -> dialogEnd = at
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
                if (split.label == TERMINALS) sections = List(4) { Section(SECTION_TERMINALS[it], it == 3) }.also { it[0].start = at }
            }
        }

        // Terminal sections see every line from the one that starts Terminals to the one that
        // starts Goldor (which isn't a terminal line, so feeding it changes nothing).
        if (sections != null && goldorWas == null) feedSections(msg, at)
    }

    /** The clear HUD: hidden once the boss has started or the run has ended. */
    fun runSplits(): List<Split> {
        val mort = mort ?: return emptyList()
        if (clearEnd() != null) return emptyList()
        val out = mutableListOf(Split("&4Blood", true, mort, blood))
        blood?.let {
            out += Split("&cWatcher Dialog", true, it, dialogEnd)
            out += Split("&cWatcher", true, it, proven)
        }
        proven?.let { out += Split("&dPortal Enter", true, it, null) }
        out += Split("&9Boss Entry", false, mort, null)
        return out
    }

    /** The Watcher HUD: the dialogue, how long until it moved, and the whole fight. */
    fun watcherSplits(): List<Split> {
        val blood = blood ?: return emptyList()
        if (clearEnd() != null) return emptyList()
        return listOf(
            Split("&cWatcher Dialog", true, blood, dialogEnd),
            Split("&cWatcher Move", false, blood, watcherMove ?: proven),
            Split("&cWatcher", true, blood, proven),
        )
    }

    /** The boss HUD: this floor's phases, the terminal sections inside Terminals, and the total. */
    fun bossSplits(): List<Split> {
        val floorStart = floorStart ?: return emptyList()
        val floor = FLOORS[floorNo] ?: return emptyList()
        val out = mutableListOf<Split>()
        floor.splits.forEachIndexed { i, split ->
            val start = (if (i == 0) floorStart else bossStarts[i]) ?: return@forEachIndexed
            if (split.label == WITHER_KING && !master) return@forEachIndexed
            val next = (i + 1 until floor.splits.size).firstNotNullOfOrNull { bossStarts[it] }
            out += Split(split.label, split.long, start, next ?: end)
            if (split.label == TERMINALS) out += terminalSplits()
        }
        out += Split("&4Boss", false, floorStart, end)
        return out
    }

    private fun terminalSplits(): List<Split> =
        sections.orEmpty().mapIndexedNotNull { i, s -> s.start?.let { Split("&eS${i + 1}", true, it, s.stop, sub = true) } }

    /** When the clear stopped being the thing you're timing: the boss starting, or the run ending. */
    private fun clearEnd(): Stamp? = listOfNotNull(floorStart, end).minByOrNull { it.realMs }

    private fun goldorStart(): Stamp? {
        val floor = FLOORS[floorNo] ?: return null
        val i = floor.splits.indexOfFirst { it.label == GOLDOR }
        return if (i < 0) null else bossStarts.getOrNull(i)
    }

    /**
     * Devonian's terminal sections, quirks and all. Each section ends when its terminals, both
     * levers, the device and (S1-S3) the gate are done; the next then starts on the same line and
     * ignores it. The device bookkeeping is odd because Hypixel's device line carries no section:
     * a repeated count means the message belongs to a later section, so the credit is pushed there.
     */
    private fun feedSections(msg: String, at: Stamp) {
        val secs = sections ?: return
        secs.forEachIndexed { i, s ->
            if (!s.active) return@forEachIndexed
            if (s.ignoreFirst) { s.ignoreFirst = false; return@forEachIndexed }
            if (msg == GATE_DESTROYED) {
                s.gateDestroyed = true
            } else {
                val m = TERMINAL_LINE.find(msg) ?: return@forEachIndexed
                val (ign, type, indexText) = m.destructured
                val index = indexText.toInt()
                if (index == s.lastIndex) {
                    if (ign == s.lastIgn) return@forEachIndexed
                    if (type == "device") {
                        when (i) {
                            0 -> if (!secs[3].deviceDone) secs[3].deviceDone = true
                                 else if (!secs[1].deviceDone) secs[1].deviceDone = true
                                 else secs[2].deviceDone = true
                            1 -> if (!secs[2].deviceDone) secs[2].deviceDone = true else secs[3].deviceDone = true
                            2 -> secs[3].deviceDone = true
                        }
                        return@forEachIndexed
                    }
                    if (s.lastType == "device") s.deviceDone = false
                } else if (index == 2 && s.lastIndex == 0) {
                    s.deviceDone = true
                } else if (index == 1 && type != "device") {
                    s.deviceDone = false
                }
                when (type) {
                    "terminal" -> s.termsDone++
                    "lever" -> s.leversDone++
                    else -> {
                        if (s.deviceDone && i == 1) secs[3].deviceDone = true
                        s.deviceDone = true
                    }
                }
                s.lastIgn = ign; s.lastIndex = index; s.lastType = type
            }
            if (s.termsDone >= s.terms && s.leversDone >= 2 && s.deviceDone && s.gateDestroyed) {
                s.stop = at
                if (i < 3) { secs[i + 1].ignoreFirst = true; secs[i + 1].start = at }
            }
        }
    }

    private class Section(val terms: Int, var gateDestroyed: Boolean) {
        var termsDone = 0
        var leversDone = 0
        var deviceDone = false
        var lastIgn = ""
        var lastIndex = 0
        var lastType = ""
        var ignoreFirst = false
        var start: Stamp? = null
        var stop: Stamp? = null
        val active get() = start != null && stop == null
    }

    private class BossSplit(val label: String, val long: Boolean = false, val starts: ((String) -> Boolean)? = null)
    private class FloorSplits(val start: String, val splits: List<BossSplit>)

    private companion object {
        const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
        const val WATCHER_DIALOG_END = "[BOSS] The Watcher: Let's see how you can handle this."
        const val WATCHER_END = "[BOSS] The Watcher: You have proven yourself. You may pass."
        const val GATE_DESTROYED = "The gate has been destroyed!"
        const val TERMINALS = "&6Terminals"
        const val GOLDOR = "&8Goldor"
        const val WITHER_KING = "&0Wither King"
        val BLOOD_OPEN = Regex("^(\\[BOSS] The Watcher: .+?|The BLOOD DOOR has been opened!)$")
        val EXTRA_STATS = Regex("^ +> EXTRA STATS <$")
        val TERMINAL_LINE = Regex("^(\\w+) (?:activated|completed) a (terminal|lever|device)! \\((\\d)/\\d\\)$")
        // Terminals starts on the first one done, or on Goldor's greeting if the team is that fast.
        val TERMINALS_START = Regex("^(?:\\w+ (?:activated|completed) a (?:terminal|lever|device)! \\(\\d/\\d\\)|\\[BOSS] Goldor: Who dares trespass into my domain\\?)$")
        /** Terminals per section: S1 4, S2 5, S3 4, S4 4. S4 has no gate. */
        val SECTION_TERMINALS = intArrayOf(4, 5, 4, 4)

        /** Each floor: the line its boss starts on, then its phases (the first starts with the boss). */
        val FLOORS: Map<Int, FloorSplits> = mapOf(
            1 to FloorSplits("[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable.", listOf(
                BossSplit("&cFirst Phase"),
                BossSplit("&cSecond Phase") { it == "[BOSS] Bonzo: Oh I'm dead!" },
            )),
            2 to FloorSplits("[BOSS] Scarf: This is where the journey ends for you, Adventurers.", listOf(
                BossSplit("&7Undeads"),
                BossSplit("&8Scarf") { it == "[BOSS] Scarf: Those toys are not strong enough I see." },
            )),
            3 to FloorSplits("[BOSS] The Professor: I was burdened with terrible news recently...", listOf(
                BossSplit("&3Guardians"),
                BossSplit("&eHuman :(") { it == "[BOSS] The Professor: Oh? You found my Guardians' one weakness?" },
                BossSplit("&dGuardian :)") { it == "[BOSS] The Professor: I see. You have forced me to use my ultimate technique." },
            )),
            4 to FloorSplits("[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!", listOf(
                BossSplit("&aThorn"),
            )),
            5 to FloorSplits("[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.", listOf(
                BossSplit("&fLivid"),
            )),
            6 to FloorSplits("[BOSS] Sadan: So you made it all the way here... Now you wish to defy me? Sadan?!", listOf(
                BossSplit("&cTerracottas"),
                BossSplit("&5Giants") { it == "[BOSS] Sadan: ENOUGH!" },
                BossSplit("&6Sadan") { it == "[BOSS] Sadan: You did it. I understand now, you have earned my respect." },
            )),
            7 to FloorSplits("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", listOf(
                BossSplit("&5Maxor"),
                BossSplit("&9Storm", long = true) { it == "[BOSS] Storm: Pathetic Maxor, just like expected." },
                BossSplit(TERMINALS) { TERMINALS_START.matches(it) },
                BossSplit(GOLDOR) { it == "The Core entrance is opening!" },
                BossSplit("&4Necron") { it == "[BOSS] Necron: You went further than any human before, congratulations." },
                BossSplit(WITHER_KING) { it == "[BOSS] Necron: All this, for nothing..." },
            )),
        )
    }
}
