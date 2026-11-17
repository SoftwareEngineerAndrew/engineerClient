package com.engineerclient.misc

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.TickEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.features.impl.dungeon.puzzlesolvers.PuzzleSolvers
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.render.text
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Puzzle Overlay: "Puzzles(N):" then a line per puzzle, green once completed and red otherwise, "???" for one not found
 * yet. Always in the tab list's order: each line stays where the tab list has it, so a puzzle found later fills its own
 * "???" instead of moving to the bottom. The Classic style draws it the way ADM's Puzzle HUD did, in the same order.
 */
object PuzzleOverlay {
    private val mc: Minecraft get() = Minecraft.getInstance()
    private val dropdown = DropdownSetting("Overlay", desc = "Shows settings for the puzzle overlay.")
    private val hud = PuzzleSolvers.HUD("Puzzle Overlay", "The dungeon's puzzles in the tab list's order: green done, red not, ??? not found yet.", true, 10, 60, 1f) { example ->
        val s = if (example) EXAMPLE else section?.takeIf { shown() } ?: return@HUD 0 to 0
        if (styleSetting.value == Style.CLASSIC) drawClassic(s) else draw(s)
    }.withDependency { dropdown.value }
    private val beforeStartSetting = BooleanSetting("Before Start", true, desc = "Shows before the run starts (dtmap's default).").withDependency { dropdown.value }
    private val duringClearSetting = BooleanSetting("During Clear", false, desc = "Shows during the clear too, until the boss.").withDependency { dropdown.value }
    private val styleSetting = SelectorSetting("Overlay Style", Style.ADM, desc = "ADM: dtmap's list. Classic: the Puzzle HUD.").withDependency { dropdown.value }
    private val playerNamesSetting = BooleanSetting("Overlay Player Names", true, desc = "Shows who solved or failed each puzzle.").withDependency { dropdown.value && styleSetting.value == Style.CLASSIC }
    private val beforeStart get() = beforeStartSetting.value
    private val duringClear get() = duringClearSetting.value
    private val playerNames get() = playerNamesSetting.value

    private enum class Style(private val label: String) { ADM("ADM"), CLASSIC("Classic"); override fun toString() = label }

    /** The tab list's puzzle section, read once a tick. */
    private var section: TabPuzzles.Section? = null
    /** Mort's map line has come: the run has started. */
    private var started = false

    private val EXAMPLE = TabPuzzles.Section(3, listOf(
        TabPuzzles.Slot("Tic Tac Toe", TabPuzzles.Status.COMPLETED),
        TabPuzzles.Slot("Quiz", TabPuzzles.Status.FAILED, "odtheking"),
        TabPuzzles.Slot(null, TabPuzzles.Status.INCOMPLETE),
    ))

    private val GREEN = Color(0, 255, 0)
    private val RED = Color(255, 0, 0)

    // shown() before the run starts, and also when the run starting would show it.
    private fun mayShow(): Boolean {
        if (!DungeonUtils.inDungeons || DungeonUtils.inBoss) return false
        return if (started) duringClear else beforeStart || duringClear
    }

    private fun shown(): Boolean {
        if (!DungeonUtils.inDungeons || DungeonUtils.inBoss) return false
        return if (started) duringClear else beforeStart
    }

    // Lines of the last section drawn, made again only when the section (or the player names setting) changes.
    private var linesFor: TabPuzzles.Section? = null
    private var linesClassic = false
    private var linesNames = false
    private var lines = emptyArray<String>()
    private var lineColors = emptyArray<Color>()

    private fun linesOf(s: TabPuzzles.Section, classic: Boolean): Array<String> {
        val names = classic && playerNames
        if (s === linesFor && classic == linesClassic && names == linesNames) return lines
        linesFor = s; linesClassic = classic; linesNames = names
        if (classic) {
            val count = maxOf(s.count ?: s.slots.size, s.slots.size)
            lines = if (count == 0) emptyArray() else Array(count + 1) { i ->
                if (i == 0) return@Array "§5Puzzles §8(§3$count§8)"
                val slot = s.slots.getOrNull(i - 1)
                val (icon, color) = when (slot?.status ?: TabPuzzles.Status.INCOMPLETE) {
                    TabPuzzles.Status.COMPLETED -> "✔" to "§a"
                    TabPuzzles.Status.FAILED -> "✖" to "§c"
                    TabPuzzles.Status.INCOMPLETE -> "✦" to "§6"
                }
                val player = if (names && slot?.player != null) " §7(§6${slot.player}§7)" else ""
                "§e${slot?.name ?: "???"}§8: [$color$icon§8]$player"
            }
        } else {
            val rows = TabPuzzles.overlayLines(s)
            lines = Array(rows.size) { rows[it].first }
            lineColors = Array(rows.size) { when (rows[it].second) { null -> Colors.WHITE; true -> GREEN; false -> RED } }
        }
        return lines
    }

    private fun GuiGraphicsExtractor.draw(s: TabPuzzles.Section): Pair<Int, Int> {
        val rows = linesOf(s, false)
        val colors = lineColors
        val step = mc.font.lineHeight + 2
        var width = 0
        for (i in rows.indices) {
            text(rows[i], 0, i * step, colors[i], shadow = true)
            width = maxOf(width, mc.font.width(rows[i]))
        }
        return width to rows.size * step - 2
    }

    private fun GuiGraphicsExtractor.drawClassic(s: TabPuzzles.Section): Pair<Int, Int> {
        val rows = linesOf(s, true)
        if (rows.isEmpty()) return 0 to 0
        val lineHeight = 12
        var width = 0
        for (i in rows.indices) {
            text(rows[i], 0, i * lineHeight, Colors.WHITE)
            width = maxOf(width, mc.font.width(rows[i]))
        }
        return width to rows.size * lineHeight
    }

    fun install() {
        // Last of Puzzles' additions: the solvers, then timers and draft, then this.
        PuzzleSolvers.moveToEnd(listOf("Puzzle Timers", "Draft", "Draft Mode"))
        for (s in listOf(dropdown, hud, beforeStartSetting, duringClearSetting, styleSetting, playerNamesSetting)) PuzzleSolvers.registerSetting(s)
        on<LevelEvent.Load> { started = false; section = null }
        on<MessageEvent.Chat> { if (message.contains(MORT_START)) started = true }
        on<TickEvent.End> {
            section = if (PuzzleSolvers.enabled && hud.value.enabled && mayShow()) TabPuzzles.read().takeIf { it != section } ?: section else null
        }
        EventBus.subscribe(this)
    }

    private const val MORT_START = "Mort: Here, I found this map when I first entered the dungeon."
}
