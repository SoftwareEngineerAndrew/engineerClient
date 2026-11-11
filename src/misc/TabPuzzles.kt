package com.engineerclient.misc

import com.odtheking.odin.OdinMod.mc
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.world.level.GameType

/**
 * The tab list's puzzle section, in the order the tab list draws it: "Puzzles: (3)", then a line per puzzle,
 * " Ice Fill: [✦]" or " ???: [✦]". Odin's own list keeps only the named ones, in the order it learned them; here every
 * line keeps its place, "???" included, so a puzzle found later doesn't move down.
 */
object TabPuzzles {
    enum class Status { INCOMPLETE, COMPLETED, FAILED }

    /** One puzzle line: its name (null for "???") and status. */
    data class Slot(val name: String?, val status: Status)

    /** The section: the header's count (null when there's no header) and the lines in order. */
    data class Section(val count: Int?, val slots: List<Slot>)

    /** The tab list's puzzle section now (empty when there's no tab list). */
    fun read(): Section {
        val infos = mc.connection?.listedOnlinePlayers ?: return Section(null, emptyList())
        return parse(infos.sortedWith(TAB_ORDER).mapNotNull { it.tabListDisplayName?.string })
    }

    /** The puzzle section from the tab list's lines, top to bottom. */
    fun parse(lines: List<String>): Section {
        var count: Int? = null
        val slots = ArrayList<Slot>()
        for (line in lines) {
            COUNT_LINE.find(line)?.let { count = it.groupValues[1].toIntOrNull() ?: count; continue }
            val m = PUZZLE_LINE.find(line) ?: continue
            val status = when (m.groupValues[2]) { "✔" -> Status.COMPLETED; "✖" -> Status.FAILED; else -> Status.INCOMPLETE }
            slots += Slot(m.groupValues[1].takeUnless { it == "???" }, status)
        }
        return Section(count, slots)
    }

    /**
     * Puzzle Overlay's lines: "Puzzles(N):" (null: white), then the tab's lines in order (true: completed, green; false:
     * red), then "???" up to the header's count.
     */
    fun overlayLines(s: Section): List<Pair<String, Boolean?>> {
        val count = maxOf(s.count ?: s.slots.size, s.slots.size)
        val out = ArrayList<Pair<String, Boolean?>>()
        out += "Puzzles($count):" to null
        for (slot in s.slots) out += (slot.name ?: "???") to (slot.status == Status.COMPLETED)
        repeat(count - s.slots.size) { out += "???" to false }
        return out
    }

    /** The tab list's own order (PlayerTabOverlay's): list order, spectators last, then team, then profile name. */
    private val TAB_ORDER = compareByDescending<PlayerInfo> { it.tabListOrder }
        .thenBy { it.gameMode == GameType.SPECTATOR }
        .thenBy { it.team?.name ?: "" }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.profile.name() }

    private val COUNT_LINE = Regex("^Puzzles: \\((\\d+)\\)$")

    /** " Ice Fill: [✦]" or " ???: [✦]", as Odin reads them. */
    private val PUZZLE_LINE = Regex("^ (\\w+(?: \\w+)*|\\?\\?\\?): \\[([✖✔✦])] ?(?:\\((\\w+)\\))?$")
}
