package com.engineerclient.pov

import kotlin.math.roundToInt

/**
 * Where everything in [TerminalsStatus] goes, in GUI pixels at scale 1: four panels in the leap menu's order (top left, top
 * right, bottom left, bottom right, as Odin's leap menu lays out its quadrants), each with the teammate's name and how many
 * clicks are left on the first line, the terminal on the second, and under them the terminal's window on its own: every
 * slot of the chest (9 across, as many rows as it has), the clickable ones lit as the leap menu lights them, and the
 * teammate's mouse over it.
 *
 * Every panel is the same size whatever is in it (a 6-row chest fits; shorter ones leave their room at the bottom), so
 * nothing shifts when a teammate opens or closes a terminal. Nothing here touches the game: the tests check it headless.
 */
object TerminalsLayout {
    /** Inside a panel's edge, and between two panels. */
    const val PAD = 5
    const val SPACE = 4
    /** A text line's height (the font's 9, less its shadow row) and the gap under each line. */
    const val TEXT_H = 8
    const val LINE_GAP = 3
    /** A slot's pitch and the gap between two slots (so a slot is drawn CELL - GAP wide). */
    const val CELL = 11
    const val GAP = 1
    /** A chest's width and the most rows a terminal's has (Select's and Melody's 54 slots). */
    const val COLS = 9
    const val MAX_ROWS = 6
    /** The text area's width: wide enough for "Select Light Gray" and a name with "14 left" beside it. */
    const val INNER_W = 120
    /** The mouse pointer drawn over the window: its size, tip at the top left. */
    const val CURSOR_W = 7
    const val CURSOR_H = 11

    val CHEST_W = COLS * CELL - GAP
    val CHEST_AREA_H = MAX_ROWS * CELL - GAP
    val PANEL_W = PAD + INNER_W + PAD
    /** The name line, the terminal line, then the window. */
    val NAME_Y = PAD
    val TITLE_Y = NAME_Y + TEXT_H + LINE_GAP
    val CHEST_Y = TITLE_Y + TEXT_H + LINE_GAP + 1
    val PANEL_H = CHEST_Y + CHEST_AREA_H + PAD
    val TOTAL_W = PANEL_W * 2 + SPACE
    val TOTAL_H = PANEL_H * 2 + SPACE

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right get() = x + w
        val bottom get() = y + h
        fun contains(o: Rect) = o.x >= x && o.y >= y && o.right <= right && o.bottom <= bottom
        fun overlaps(o: Rect) = x < o.right && o.x < right && y < o.bottom && o.y < bottom
    }

    /** Leap menu slot [i] (0-3, Odin's leapTeammates order): its panel. */
    fun panel(i: Int): Rect {
        require(i in 0..3) { "leap slot $i" }
        return Rect((i % 2) * (PANEL_W + SPACE), (i / 2) * (PANEL_H + SPACE), PANEL_W, PANEL_H)
    }

    /** A terminal window of [rows] rows inside [panel]: centred across, just under the two lines whatever its height. */
    fun chest(panel: Rect, rows: Int): Rect {
        require(rows in 1..MAX_ROWS) { "chest of $rows rows" }
        return Rect(panel.x + PAD + (INNER_W - CHEST_W) / 2, panel.y + CHEST_Y, CHEST_W, rows * CELL - GAP)
    }

    /** Rows of a chest of [slots] slots. */
    fun rows(slots: Int) = ((slots + COLS - 1) / COLS).coerceIn(1, MAX_ROWS)

    /** Chest slot [slot] (row by row from the top left, as the game numbers them) in [chest]. */
    fun slot(chest: Rect, slot: Int) = Rect(chest.x + (slot % COLS) * CELL, chest.y + (slot / COLS) * CELL, CELL - GAP, CELL - GAP)

    /** Where text whose ink is [ink] wide starts so its middle is the slot's middle (a number on a slot). */
    fun centred(cell: Rect, ink: Int) = cell.x + (cell.w - ink + 1) / 2

    /** Where text whose ink is [ink] wide starts so it ends at the text area's right edge (the same [PAD] from the panel's edge as the left). */
    fun rightAligned(panel: Rect, ink: Int) = panel.x + PAD + INNER_W - ink

    /**
     * The mouse's tip in [chest], from where it is over the chest in slots ([u] across, [v] down, from the first slot's top
     * left corner; 1 is a whole slot): kept inside [panel] so a mouse off the window still shows at its edge.
     */
    fun cursor(panel: Rect, chest: Rect, u: Double, v: Double): Pair<Int, Int> = Pair(
        (chest.x + u * CELL).roundToInt().coerceIn(panel.x + 1, panel.right - CURSOR_W - 1),
        (chest.y + v * CELL).roundToInt().coerceIn(panel.y + 1, panel.bottom - CURSOR_H - 1),
    )

    /**
     * The mouse in a terminal frame: each of across and down in one byte, sixteenths of a slot from a slot before the first
     * (so -1 to about 14.9 slots); [NO_MOUSE] when it isn't known (Odin's custom terminal GUI off a slot, say).
     */
    const val NO_MOUSE = 255
    fun encodeMouse(slots: Double): Int = if (slots.isNaN()) NO_MOUSE else ((slots + 1) * 16).roundToInt().coerceIn(0, NO_MOUSE - 1)
    fun decodeMouse(b: Int): Double? = if (b !in 0 until NO_MOUSE) null else b / 16.0 - 1

    /**
     * The pointer, its tip at the top left: E its black edge, F its white inside (so it reads on lit slots and dark ones),
     * [CURSOR_W] by [CURSOR_H].
     */
    val CURSOR = listOf(
        "E......",
        "EE.....",
        "EFE....",
        "EFFE...",
        "EFFFE..",
        "EFFFFE.",
        "EFFFFFE",
        "EFFEEEE",
        "EFE....",
        "EE.....",
        "E......",
    )

    /** [text], cut short with ".." if it's wider than [max] by [width] (the font's). */
    fun fit(text: String, max: Int, width: (String) -> Int): String {
        if (width(text) <= max) return text
        var n = text.length
        while (n > 0 && width(text.substring(0, n) + "..") > max) n--
        return text.substring(0, n) + ".."
    }

    /** The name, fitted beside [count] (right-aligned on the same line) with room between them. */
    fun nameBeside(name: String, count: String, width: (String) -> Int): String =
        fit(name, INNER_W - (if (count.isEmpty()) 0 else width(count) + SPACE * 2), width)

    /** A teammate not in a terminal: their name and [IDLE] under it, each centred in the panel (where text whose ink is [ink] wide starts, and its line). */
    const val IDLE = "not in a terminal"
    fun idleName(panel: Rect, ink: Int) = Pair(panel.x + (panel.w - ink) / 2, panel.y + (panel.h - (TEXT_H * 2 + LINE_GAP)) / 2)
    fun idleNote(panel: Rect, ink: Int) = Pair(panel.x + (panel.w - ink) / 2, idleName(panel, 0).second + TEXT_H + LINE_GAP)

    /** "N left", or nothing for Melody (it isn't clicks). */
    fun left(cells: Int, melody: Boolean) = if (melody) "" else "$cells left"
}
