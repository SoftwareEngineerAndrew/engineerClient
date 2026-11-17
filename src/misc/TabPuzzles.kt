package com.engineerclient.misc

import com.adm.AdmMod.mc
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component
import net.minecraft.world.level.GameType
import java.util.IdentityHashMap

/**
 * The tab list's puzzle section, in the order the tab list draws it: "Puzzles: (3)", then a line per puzzle,
 * " Ice Fill: [✦]" or " ???: [✦]". ADM's own list keeps only the named ones, in the order it learned them; here every
 * line keeps its place, "???" included, so a puzzle found later doesn't move down.
 */
object TabPuzzles {
    enum class Status { INCOMPLETE, COMPLETED, FAILED }

    /** One puzzle line: its name (null for "???"), status and who solved or failed it. */
    data class Slot(val name: String?, val status: Status, val player: String? = null)

    /** The section: the header's count (null when there's no header) and the lines in order. */
    data class Section(val count: Int?, val slots: List<Slot>)

    /** The tab list's puzzle section now (empty when there's no tab list). */
    fun read(): Section {
        val infos = mc.connection?.listedOnlinePlayers ?: return Section(null, emptyList())
        if (memo.size > 512) memo.clear()
        val hits = ArrayList<Line>()
        for (info in infos) {
            val name = info.tabListDisplayName ?: continue
            val line = memo[info]?.takeIf { it.name === name } ?: lineOf(info, name).also { memo[info] = it }
            if (line.counts || line.slot != null) hits += line
        }
        // Sorting only the puzzle lines keeps them in the full list's order: the sort is stable.
        hits.sortWith(LINE_ORDER)
        var count: Int? = null
        val slots = ArrayList<Slot>()
        for (line in hits) {
            if (line.counts) count = line.count ?: count
            else slots += line.slot!!
        }
        return Section(count, slots)
    }

    // A tab line's parse, kept while its display name is the same Component (a new name always arrives as a new one).
    private class Line(val info: PlayerInfo, val name: Component, val counts: Boolean, val count: Int?, val slot: Slot?)

    private val memo = IdentityHashMap<PlayerInfo, Line>()

    private fun lineOf(info: PlayerInfo, name: Component): Line {
        val text = name.string
        COUNT_LINE.find(text)?.let { return Line(info, name, true, it.groupValues[1].toIntOrNull(), null) }
        return Line(info, name, false, null, slotOf(text))
    }

    private fun slotOf(line: String): Slot? {
        val m = PUZZLE_LINE.find(line) ?: return null
        val status = when (m.groupValues[2]) { "✔" -> Status.COMPLETED; "✖" -> Status.FAILED; else -> Status.INCOMPLETE }
        return Slot(m.groupValues[1].takeUnless { it == "???" }, status, m.groupValues[3].ifEmpty { null })
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
    private val LINE_ORDER = compareBy(TAB_ORDER) { line: Line -> line.info }

    private val COUNT_LINE = Regex("^Puzzles: \\((\\d+)\\)$")

    /** " Ice Fill: [✦]" or " ???: [✦]", as ADM reads them. */
    private val PUZZLE_LINE = Regex("^ (\\w+(?: \\w+)*|\\?\\?\\?): \\[([✖✔✦])] ?(?:\\((\\w+)\\))?$")
}
