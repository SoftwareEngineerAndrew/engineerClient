package com.engineerclient.pov

import com.engineerclient.pov.TerminalsLayout.Rect
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalTypes
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.DyeColor

/**
 * The terminals your teammates have open, as the leap menu's POV views show them (shared over [PoseShare]: the type, the
 * letter or colour, and Odin's solution on their screen), in a HUD split the way the leap menu is: each teammate in the
 * quarter their leap menu slot has. Shown only while one of them has a terminal open.
 */
object TerminalsStatus : Module(
    name = "Terminals Status",
    key = null,
    category = Category.custom("Engineer Client", 860, 10),
    description = "A HUD of the terminals your teammates have open, each in their leap menu quarter: the terminal's window on its own, what's left to click, and their mouse. Shown only while one of them is in a terminal. Uses the same sharing as POV Previews (their Sharing FPS must not be none).",
    toggled = true,
) {
    private val idle by BooleanSetting("Show Idle Teammates", true, desc = "While someone is in a terminal, the teammates who aren't are shown too (faintly, just their name), so each stays in their leap menu quarter.")

    private val hud by HUD("Terminals Status", "Your teammates' open terminals, in leap menu order. Only shown while one of them is in a terminal.", true, 10, 60, 1f) { example ->
        draw(this, example)
    }

    private val WHITE = Color(255, 255, 255)
    private val GRAY = Color(150, 150, 150)
    private val DIM = Color(120, 120, 120)
    private val TITLE = Color(255, 210, 90)

    private class Shown(val name: String, val clazz: DungeonClass, val gui: PoseShare.Gui?)

    /** What the HUD editor shows: two teammates in terminals, one not, one on Melody. */
    private val EXAMPLE by lazy {
        listOf(
            Shown("Archer", DungeonClass.ARCHER, PoseShare.Gui(TerminalTypes.NUMBERS, 0, intArrayOf(14, 15, 16, 21, 22, 23), 0L, 5.4, 1.6)),
            Shown("Mage", DungeonClass.MAGE, null),
            Shown("Healer", DungeonClass.HEALER, PoseShare.Gui(TerminalTypes.SELECT, DyeColor.LIGHT_GRAY.ordinal, intArrayOf(10, 13, 20, 24, 30, 41), 0L, 2.5, 2.3)),
            Shown("Tank", DungeonClass.TANK, PoseShare.Gui(TerminalTypes.MELODY, 0, intArrayOf(3, 39, 43), 0L, 7.5, 4.5)),
        )
    }

    private fun draw(gfx: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val shown = if (example) EXAMPLE else {
            if (!DungeonUtils.inDungeons) return 0 to 0
            val now = System.nanoTime()
            (0 until 4).map { i ->
                val p = DungeonUtils.leapTeammates.getOrNull(i)?.takeIf { it.name != "Empty" }
                p?.let { Shown(it.name, it.clazz, PoseShare.gui(it.name, now)) }
            }.also { list -> if (list.none { it?.gui != null }) return 0 to 0 }
        }
        shown.forEachIndexed { i, s -> if (s != null && (s.gui != null || idle || example)) panel(gfx, TerminalsLayout.panel(i), s) }
        return TerminalsLayout.TOTAL_W to TerminalsLayout.TOTAL_H
    }

    private fun panel(gfx: GuiGraphicsExtractor, p: Rect, s: Shown) {
        val t = s.gui
        val width = { str: String -> mc.font.width(str) }
        val accent = s.clazz.color.rgba
        gfx.fill(p.x, p.y, p.right, p.bottom, if (t != null) 0xA0000000.toInt() else 0x50000000)
        // The class colour along the top: whose quarter it is, at a glance.
        gfx.fill(p.x, p.y, p.right, p.y + 1, if (t != null) accent else (accent and 0x00FFFFFF) or 0x60000000)
        if (t == null) {
            // Not in a terminal: just who, in the middle of their quarter.
            mice.remove(s.name)
            val name = TerminalsLayout.fit(s.name, TerminalsLayout.INNER_W, width)
            TerminalsLayout.idleName(p, width(name) - 1).let { (nx, ny) -> gfx.text(name, nx, ny, GRAY, true) }
            TerminalsLayout.idleNote(p, width(TerminalsLayout.IDLE) - 1).let { (nx, ny) -> gfx.text(TerminalsLayout.IDLE, nx, ny, DIM, true) }
            return
        }
        val x = p.x + TerminalsLayout.PAD
        val count = TerminalsLayout.left(t.solution.distinct().size, t.type == TerminalTypes.MELODY)
        gfx.text(TerminalsLayout.nameBeside(s.name, count, width), x, p.y + TerminalsLayout.NAME_Y, WHITE, true)
        if (count.isNotEmpty()) gfx.text(count, TerminalsLayout.rightAligned(p, width(count) - 1), p.y + TerminalsLayout.NAME_Y, GRAY, true)
        gfx.text(TerminalsLayout.fit(title(t), TerminalsLayout.INNER_W, width), x, p.y + TerminalsLayout.TITLE_Y, TITLE, true)
        val grid = PoseShare.GRIDS[t.type] ?: return
        // Their terminal's window on its own: every slot of the chest, the ones that aren't the terminal's as its glass.
        val chest = TerminalsLayout.chest(p, TerminalsLayout.rows(t.type.windowSize))
        gfx.fill(chest.x - 1, chest.y - 1, chest.right + 1, chest.bottom + 1, 0x40000000)
        val clicks = HashMap<Int, Int>(); val order = HashMap<Int, Int>()
        t.solution.forEachIndexed { n, slot -> clicks.merge(slot, 1, Int::plus); order.putIfAbsent(slot, n) }
        for (slot in 0 until TerminalsLayout.rows(t.type.windowSize) * TerminalsLayout.COLS) {
            val cell = TerminalsLayout.slot(chest, slot)
            if (slot !in grid) { gfx.fill(cell.x, cell.y, cell.right, cell.bottom, GLASS); continue }
            val n = order[slot]
            gfx.fill(cell.x, cell.y, cell.right, cell.bottom, if (n == null && t.type != TerminalTypes.PANES) UNLIT else cellColor(t.type, n))
            val label = when {
                t.type == TerminalTypes.RUBIX -> clicks[slot]?.toString()
                t.type == TerminalTypes.NUMBERS && n != null && n < 3 -> "${n + 1}"
                else -> null
            } ?: continue
            gfx.text(label, TerminalsLayout.centred(cell, width(label) - 1), cell.y + 1, WHITE, true)
        }
        mouse(s.name, t)?.let { (u, v) -> cursor(gfx, TerminalsLayout.cursor(p, chest, u, v)) }
    }

    /** A slot that isn't the terminal's (the chest's filler glass). */
    private const val GLASS = 0x10FFFFFF
    /** A slot of the terminal's with nothing to click on it (lighter than the glass, so the terminal stands out of its chest). */
    private const val UNLIT = 0x30FFFFFF

    /** Each teammate's mouse as drawn: eased toward where they last said it was, so it glides between their ticks. */
    private class Mouse(var u: Double, var v: Double, var at: Long, var type: TerminalTypes)
    private val mice = HashMap<String, Mouse>()

    private fun mouse(name: String, t: PoseShare.Gui): Pair<Double, Double>? {
        if (t.mouseU.isNaN() || t.mouseV.isNaN()) return null
        val now = System.nanoTime()
        val m = mice[name]
        // A new terminal (or none drawn yet) starts where it is; within one, about 40 ms behind (k = 25/s).
        if (m == null || m.type != t.type) { mice[name] = Mouse(t.mouseU, t.mouseV, now, t.type); return t.mouseU to t.mouseV }
        val k = 1 - Math.exp(-((now - m.at) / 1e9).coerceIn(0.0, 0.25) * 25)
        m.u += (t.mouseU - m.u) * k; m.v += (t.mouseV - m.v) * k; m.at = now
        return m.u to m.v
    }

    private fun cursor(gfx: GuiGraphicsExtractor, at: Pair<Int, Int>) {
        val (cx, cy) = at
        TerminalsLayout.CURSOR.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                val color = when (c) { 'E' -> 0xFF000000.toInt(); 'F' -> 0xFFFFFFFF.toInt(); else -> return@forEachIndexed }
                gfx.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, color)
            }
        }
    }

    /** The terminal's name as the leap menu writes it, with the letter or colour (Starts With, Select). */
    fun title(t: PoseShare.Gui): String = when (t.type) {
        TerminalTypes.PANES -> "Panes"
        TerminalTypes.RUBIX -> "Rubix"
        TerminalTypes.NUMBERS -> "Numbers"
        TerminalTypes.STARTS_WITH -> "Starts With '${'A' + t.extra}'"
        TerminalTypes.SELECT -> "Select " + DyeColor.entries[t.extra].name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        TerminalTypes.MELODY -> "Melody"
    }

    /** A cell's colour, as the leap menu's: [n] is where it comes in the solution (null: not in it). */
    fun cellColor(type: TerminalTypes, n: Int?): Int = when (type) {
        // Red: still to click; green: done.
        TerminalTypes.PANES -> if (n != null) 0xE0D04040.toInt() else 0xE040B040.toInt()
        TerminalTypes.NUMBERS -> when (n) { null -> 0x60303030; 0 -> 0xF040D040.toInt(); 1 -> 0xE0D0C030.toInt(); 2 -> 0xD0D07020.toInt(); else -> 0xC0A04040.toInt() }
        // Melody's solution: the lane's green pane, the pointer, and the button when they line up.
        TerminalTypes.MELODY -> when (n) { 0 -> 0xF040D040.toInt(); 1 -> 0xF0D040D0.toInt(); 2 -> 0xF080FF80.toInt(); else -> 0x40303030 }
        else -> if (n != null) 0xE040D040.toInt() else 0x60303030
    }
}
