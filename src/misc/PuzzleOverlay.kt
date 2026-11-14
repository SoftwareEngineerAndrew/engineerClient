package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Puzzle Overlay: dtmap's puzzle HUD (github.com/ricedotwho/dtMap, PuzzleHud.kt), "Puzzles(N):" then a line per puzzle,
 * green once completed and red otherwise, "???" for one not found yet. Always in the tab list's order: each line stays
 * where the tab list has it, so a puzzle found later fills its own "???" instead of moving to the bottom (dtmap's
 * default lists them in map scan order). The Odin style draws it the way Odin's Puzzle HUD did, in the same order.
 */
object PuzzleOverlay : Module(
    name = "Puzzle Overlay",
    category = Category.custom("Engineer Client", 860, 10),
    description = "dtmap's puzzle list (green done, red not, ??? not found yet), always in the tab list's order.",
    key = null,
) {
    private val beforeStart by BooleanSetting("Before Start", true, desc = "Shows before the run starts (dtmap's default).")
    private val duringClear by BooleanSetting("During Clear", false, desc = "Shows during the clear too, until the boss.")
    private val style by SelectorSetting("Style", Style.ADM, desc = "ADM: dtmap's list. Odin: Odin's Puzzle HUD.")
    private val playerNames by BooleanSetting("Player Names", true, desc = "Shows who solved or failed each puzzle.").withDependency { style == Style.ODIN }

    private enum class Style(private val label: String) { ADM("ADM"), ODIN("Odin"); override fun toString() = label }

    private val hud by HUD("Puzzle Overlay", "The dungeon's puzzles in the tab list's order: green done, red not, ??? not found yet.", true, 10, 60, 1f) { example ->
        val s = if (example) EXAMPLE else section?.takeIf { shown() } ?: return@HUD 0 to 0
        if (style == Style.ODIN) drawOdin(s) else draw(s)
    }

    /** The tab list's puzzle section, read once a tick. */
    private var section: TabPuzzles.Section? = null
    /** Mort's map line has come: the run has started. */
    private var started = false

    private val EXAMPLE = TabPuzzles.Section(3, listOf(
        TabPuzzles.Slot("Tic Tac Toe", TabPuzzles.Status.COMPLETED),
        TabPuzzles.Slot("Quiz", TabPuzzles.Status.FAILED, "odtheking"),
        TabPuzzles.Slot(null, TabPuzzles.Status.INCOMPLETE),
    ))

    /** dtmap's colours (java.awt.Color.GREEN and RED). */
    private val GREEN = Color(0, 255, 0)
    private val RED = Color(255, 0, 0)

    private fun shown(): Boolean {
        if (!DungeonUtils.inDungeons || DungeonUtils.inBoss) return false
        return if (started) duringClear else beforeStart
    }

    private fun GuiGraphicsExtractor.draw(s: TabPuzzles.Section): Pair<Int, Int> {
        val rows = TabPuzzles.overlayLines(s)
        val step = mc.font.lineHeight + 2
        var width = 0
        rows.forEachIndexed { i, (label, done) ->
            text(label, 0, i * step, when (done) { null -> Colors.WHITE; true -> GREEN; false -> RED }, shadow = true)
            width = maxOf(width, mc.font.width(label))
        }
        return width to rows.size * step - 2
    }

    private fun GuiGraphicsExtractor.drawOdin(s: TabPuzzles.Section): Pair<Int, Int> {
        val count = maxOf(s.count ?: s.slots.size, s.slots.size)
        if (count == 0) return 0 to 0
        val lineHeight = 12
        var width = textDim("§5Puzzles §8(§3$count§8)", 0, 0, Colors.WHITE).first
        val slots = s.slots + List(count - s.slots.size) { TabPuzzles.Slot(null, TabPuzzles.Status.INCOMPLETE) }
        slots.forEachIndexed { i, slot ->
            val (icon, color) = when (slot.status) {
                TabPuzzles.Status.COMPLETED -> "✔" to "§a"
                TabPuzzles.Status.FAILED -> "✖" to "§c"
                TabPuzzles.Status.INCOMPLETE -> "✦" to "§6"
            }
            val player = if (playerNames && slot.player != null) " §7(§6${slot.player}§7)" else ""
            width = maxOf(width, textDim("§e${slot.name ?: "???"}§8: [$color$icon§8]$player", 0, (i + 1) * lineHeight, Colors.WHITE).first)
        }
        return width to (slots.size + 1) * lineHeight
    }

    init {
        on<LevelEvent.Load> { started = false; section = null }
        on<MessageEvent> { if (message.contains(MORT_START)) started = true }
        on<TickEvent.End> { section = if (DungeonUtils.inDungeons) TabPuzzles.read() else null }
    }

    private const val MORT_START = "Mort: Here, I found this map when I first entered the dungeon."
}
