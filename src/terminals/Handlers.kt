package com.engineerclient.terminals

import com.odtheking.odin.utils.hasGlint
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.StainedGlassPaneBlock
import kotlin.math.abs

private val RED_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.RED)
private val LIME_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.LIME)

class PanesHandler : TerminalHandler(TerminalTypes.PANES) {
    override fun key(item: ItemStack) = when {
        item.`is`(RED_PANE) -> 1
        item.`is`(LIME_PANE) -> 0
        else -> NONE
    }
    override fun next(key: Int, button: Int) = when (key) { 1 -> 0; 0 -> 1; else -> key }
    override fun canClickShown(slot: Int, button: Int) = shown[slot] == 1
    override fun buildSolution(out: ArrayList<Int>) { for (i in 0 until area) if (shown[i] == 1) out += i }
    override fun look(slot: Int) = if (shown.getOrNull(slot) == 1) Look(PANE) else null
}

class NumbersHandler : TerminalHandler(TerminalTypes.NUMBERS) {
    private val order = ArrayList<Int>()

    override fun key(item: ItemStack) = when {
        item.`is`(RED_PANE) -> item.count
        item.`is`(LIME_PANE) -> 0
        else -> NONE
    }
    override fun next(key: Int, button: Int) = if (key > 0) 0 else key
    override fun canClickShown(slot: Int, button: Int) = order.firstOrNull() == slot

    override fun buildSolution(out: ArrayList<Int>) {
        order.clear()
        val forced = orderOverride?.invoke()
        if (forced != null) order += forced.filter { (shown.getOrNull(it) ?: 0) > 0 }
        else (0 until area).filter { shown[it] > 0 }.sortedBy { shown[it] }.let(order::addAll)
        out += order
    }

    override fun look(slot: Int): Look? {
        val at = order.indexOf(slot)
        if (at < 0) return null
        val text = if (!Terminals.numberText || orderOverride?.invoke() != null || (Terminals.hidesTerminalNumbers() && !Terminals.showsTerminalNumber(slot, order))) null
            else shown[slot].toString()
        return Look(if (at < 4) NUMBER_1 + at else NONE, text)
    }

    companion object {
        /** /termsim inf: its own endless order instead of the panes'. */
        @JvmStatic var orderOverride: (() -> List<Int>?)? = null
    }
}

class RubixHandler(
    private val leftOnly: () -> Boolean = { Terminals.rubixMode == Terminals.RubixMode.LEFT_CLICKS_ONLY || Terminals.rubixMode == Terminals.RubixMode.LEFT_LEAST_MOVEMENT },
    private val leastMovement: () -> Boolean = { Terminals.rubixMode == Terminals.RubixMode.LEAST_MOVEMENT || Terminals.rubixMode == Terminals.RubixMode.LEFT_LEAST_MOVEMENT },
) : TerminalHandler(TerminalTypes.RUBIX) {
    private var target = -1

    override fun key(item: ItemStack): Int {
        val color = ((item.item as? BlockItem)?.block as? StainedGlassPaneBlock)?.color ?: return NONE
        return ORDER.indexOf(color)
    }

    override fun next(key: Int, button: Int) = if (key < 0) key else (key + (if (button == RIGHT) -1 else 1) + ORDER.size) % ORDER.size

    private fun clicks(from: Int, to: Int): Int {
        val forward = (to - from + ORDER.size) % ORDER.size
        return if (forward > 2 && !leftOnly()) forward - ORDER.size else forward
    }

    private val route = ArrayList<Int>()

    /** Fewest clicks; then fewest right clicks; then fewest panes touched. With left clicks only no two colors ever tie (9 panes). */
    override fun onReady() {
        val panes = (0 until area).filter { keys[it] >= 0 }
        if (panes.isEmpty()) return
        target = ORDER.indices.minWith(compareBy<Int>(
            { t -> panes.sumOf { abs(clicks(keys[it], t)) } },
            { t -> panes.sumOf { -minOf(0, clicks(keys[it], t)) } },
            { t -> panes.count { clicks(keys[it], t) != 0 } },
        ))
    }

    /**
     * Least Movement: the order to click [slots] in for the shortest mouse path, from the pane last clicked (first if it still
     * needs clicks) or, before any click, from whichever pane makes it shortest. Exact, over subsets (9 panes at most).
     */
    private fun shortestRoute(slots: List<Int>, from: Int): List<Int> {
        if (slots.size <= 1) return slots
        val nodes = if (from >= 0 && from !in slots) listOf(from) + slots else slots
        val n = nodes.size
        fun dist(a: Int, b: Int) = Math.hypot((nodes[a] % 9 - nodes[b] % 9).toDouble(), (nodes[a] / 9 - nodes[b] / 9).toDouble())
        val best = Array(1 shl n) { DoubleArray(n) { Double.MAX_VALUE } }
        val prev = Array(1 shl n) { IntArray(n) { -1 } }
        val starts = if (from >= 0) listOf(nodes.indexOf(from)) else nodes.indices.toList()
        for (i in starts) best[1 shl i][i] = 0.0
        for (mask in 1 until (1 shl n)) for (last in 0 until n) {
            val d = best[mask][last]
            if (d == Double.MAX_VALUE) continue
            for (next in 0 until n) if (mask and (1 shl next) == 0) {
                val m = mask or (1 shl next)
                if (d + dist(last, next) < best[m][next]) { best[m][next] = d + dist(last, next); prev[m][next] = last }
            }
        }
        val full = (1 shl n) - 1
        var last = (0 until n).minBy { best[full][it] }
        var mask = full
        val order = ArrayList<Int>()
        while (last >= 0) {
            order.add(0, nodes[last])
            val p = prev[mask][last]
            mask = mask and (1 shl last).inv()
            last = p
        }
        return order.filter { it in slots }
    }

    private fun need(slot: Int) = if (target < 0 || shown[slot] < 0) 0 else clicks(shown[slot], target)

    override fun canClickShown(slot: Int, button: Int): Boolean {
        val n = need(slot)
        return if (button == RIGHT) n < 0 else n > 0
    }

    override fun buildSolution(out: ArrayList<Int>) {
        route.clear()
        if (leastMovement()) route += shortestRoute((0 until area).filter { need(it) != 0 }, lastClicked)
        for (i in 0 until area) repeat(abs(need(i))) { out += i }
    }

    override fun look(slot: Int): Look? {
        val n = need(slot)
        if (n == 0) return null
        if (route.isNotEmpty()) return Look(if (route[0] == slot) RUBIX_1 else RUBIX_2, n.toString())
        return Look(when (n) { 1 -> RUBIX_1; 2 -> RUBIX_2; -1 -> RUBIX_M1; -2 -> RUBIX_M2; else -> if (n > 0) RUBIX_2 else RUBIX_M2 }, n.toString())
    }

    private companion object {
        val ORDER = listOf(DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE)
    }
}

class SelectHandler(color: DyeColor) : TerminalHandler(TerminalTypes.SELECT) {
    private val prefixes = when (color) {
        DyeColor.BLACK -> setOf("black", "ink")
        DyeColor.BLUE -> setOf("blue", "lapis")
        DyeColor.BROWN -> setOf("brown", "cocoa")
        DyeColor.WHITE -> setOf("white", "bone", "wool")
        DyeColor.GREEN -> setOf("green", "cactus")
        DyeColor.RED -> setOf("red", "rose")
        DyeColor.YELLOW -> setOf("yellow", "dandelion")
        DyeColor.LIGHT_GRAY -> setOf("silver", "light gray")
        else -> setOf(color.name.lowercase().replace('_', ' '))
    }

    override val anyUpdateAnswers = true
    override fun key(item: ItemStack): Int {
        if (item.isEmpty || item.`is`(BLACK_PANE) || item.hasGlint()) return 0
        val name = item.hoverName.string.lowercase()
        return if (prefixes.any(name::startsWith)) 1 else 0
    }
    override fun next(key: Int, button: Int) = 0
    override fun canClickShown(slot: Int, button: Int) = shown[slot] == 1
    override fun buildSolution(out: ArrayList<Int>) { for (i in 0 until area) if (shown[i] == 1) out += i }
    override fun look(slot: Int) = if (shown.getOrNull(slot) == 1) Look(SELECT) else null
}

class StartsWithHandler(private val letter: String) : TerminalHandler(TerminalTypes.STARTS_WITH) {
    override val anyUpdateAnswers = true
    override fun key(item: ItemStack): Int {
        if (item.isEmpty || !item.hoverName.string.startsWith(letter, true)) return 0
        return if (!item.hasGlint() || item.item in GLINT_ANYWAY) 1 else 0
    }
    override fun next(key: Int, button: Int) = 0
    override fun canClickShown(slot: Int, button: Int) = shown[slot] == 1
    override fun buildSolution(out: ArrayList<Int>) { for (i in 0 until area) if (shown[i] == 1) out += i }
    override fun look(slot: Int) = if (shown.getOrNull(slot) == 1) Look(STARTS_WITH) else null

    private companion object {
        val GLINT_ANYWAY by lazy { BuiltInRegistries.ITEM.filter { it.components().has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) }.toSet() + Items.GOLDEN_APPLE }
    }
}

/** Melody: lanes in rows 1-3 (columns 1-5), a button at column 7 of each, the column to hit marked in rows 0 and 4. */
class MelodyHandler : TerminalHandler(TerminalTypes.MELODY) {
    private var column = -1
    private var pointer = -1
    private var button = -1

    override fun key(item: ItemStack) = 0
    override fun canClickShown(slot: Int, button: Int) = slot == activeButton()

    private fun activeButton() = if (button >= 0) button else -1

    override fun buildSolution(out: ArrayList<Int>) {
        column = (0 until 9).firstOrNull { items[it].`is`(MAGENTA_PANE) } ?: -1
        pointer = (9 until 36).lastOrNull { items[it].`is`(LIME_PANE) } ?: -1
        button = BUTTONS.firstOrNull { items[it].`is`(LIME_CLAY) } ?: -1
        if (pointer >= 0) out += pointer
        if (column >= 0) { out += column; out += column + 36 }
        if (aligned()) out += button
        BUTTONS.indexOf(button).takeIf { it >= 0 && column >= 0 }?.let { rowColumns[it] = column % 9 }
    }

    /** Each row's answer column as it was shown, for the staircase. */
    private val rowColumns = IntArray(BUTTONS.size) { -1 }

    /** The pane is lined up on row 1's first slot, or on a staircase: one slot right of the row before. */
    fun alerting(): Boolean {
        if (!aligned()) return false
        val row = BUTTONS.indexOf(button)
        val col = column % 9
        return if (row == 0) col == FIRST_SLOT else row > 0 && rowColumns[row - 1] >= 0 && col == rowColumns[row - 1] + 1
    }

    private fun aligned() = pointer >= 0 && column >= 0 && button >= 0 && pointer % 9 == column % 9

    override fun look(slot: Int): Look? {
        val row = slot / 9
        val col = slot % 9
        return when {
            (row == 0 || row == 4) && col == column -> Look(MELODY_COLUMN)
            slot == pointer -> Look(MELODY_POINTER)
            slot == button && aligned() -> Look(MELODY_POINTER)
            else -> null
        }
    }

    private companion object {
        val BUTTONS = listOf(16, 25, 34)
        const val FIRST_SLOT = 1
        val MAGENTA_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.MAGENTA)
        val LIME_CLAY = Items.DYED_TERRACOTTA.pick(DyeColor.LIME)
    }
}
