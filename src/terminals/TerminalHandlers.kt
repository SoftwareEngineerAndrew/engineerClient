// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.terminals

import com.engineerclient.terminals.TerminalSolverSettings.TermColor
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.StainedGlassPaneBlock
import kotlin.math.abs

/**
 * The six terminals as Hypixel titles them, and the size of each window (the terminal's slots; the last row of each
 * is the window's border, never part of the puzzle). Odin's TerminalTypes, without its simulator and GUI hooks.
 */
enum class TerminalType(val regex: Regex, val windowSize: Int) {
    PANES(Regex("^Correct all the panes!$"), 45),
    RUBIX(Regex("^Change all to same color!$"), 45),
    NUMBERS(Regex("^Click in order!$"), 36),
    STARTS_WITH(Regex("^What starts with: '(\\w)'\\?$"), 45),
    SELECT(Regex("^Select all the ([\\w ]+) items!$"), 54),
    MELODY(Regex("^Click the button on time!$"), 54);

    companion object {
        /** The handler for a window titled [title], or null when it isn't a terminal. */
        fun openHandler(title: String): TerminalHandler? = when (entries.find { it.regex.matches(title) }) {
            PANES -> PanesHandler()
            RUBIX -> RubixHandler()
            NUMBERS -> NumbersHandler()
            STARTS_WITH -> STARTS_WITH.regex.find(title)?.groupValues?.get(1)?.let { StartsWithHandler(it) }
            SELECT -> {
                val name = SELECT.regex.find(title)?.groupValues?.get(1)?.replace("SILVER", "LIGHT GRAY")
                DyeColor.entries.find { it.name.replace("_", " ").equals(name, true) }?.let { SelectAllHandler(it) }
            }
            MELODY -> MelodyHandler()
            null -> null
        }
    }
}

/** A slot's highlight and, for Numbers and Rubix, the text on it. */
data class SlotLook(val color: Int, val text: String?)

/**
 * One open terminal: what to click ([solution], worked out again on every slot the server sends) and the clicks sent
 * that the server hasn't answered yet ([clickedSlots]), which client prediction takes out of the solution meanwhile.
 * Odin's TerminalHandler.
 */
abstract class TerminalHandler(val type: TerminalType) {
    val clickedSlots = ArrayList<Pair<Int, Int>>()
    val timeOpened = System.currentTimeMillis()
    val solution = ArrayList<Int>()
    var lastClickTime = 0L

    /** The server sent [stack] for [slotIndex] of [slots] (the open window's). */
    open fun updateSlot(slotIndex: Int, stack: ItemStack, slots: List<Slot>) {
        if (slots.isEmpty() || slotIndex !in 0 until type.windowSize - 9 || stack.item == BLACK_PANE) return

        // Answered: that click and every one before it.
        val index = clickedSlots.indexOfFirst { it.first == slotIndex }
        if (index >= 0) clickedSlots.subList(0, index + 1).clear()

        solution.clear()
        solution.addAll(solve(slots.subList(0, type.windowSize - 9), slotIndex))
        if (TerminalSolverSettings.clickPrediction.value) clickedSlots.forEach { (i, button) -> simulateClick(i, button) }
    }

    protected abstract fun renderSlot(slotIndex: Int): SlotLook?

    fun getSlotRendering(slotIndex: Int): SlotLook? = if (slotIndex !in solution) null else renderSlot(slotIndex)

    open fun simulateClick(slotIndex: Int, clickType: Int) {
        solution.removeAt(solution.indexOf(slotIndex).takeIf { it != -1 } ?: return)
    }

    abstract fun solve(slots: List<Slot>, updatedIndex: Int): List<Int>

    /** A click on [slotIndex] with container [button] (0 left, 1 right): sent on only if it's one to make. */
    open fun click(slotIndex: Int, button: Int, simulateClick: Boolean) {
        if (!canClick(slotIndex, button) || shouldProtect()) return

        // Middle clicks everywhere but Rubix's right ones: they never pick up an item.
        val mappedButton = if (button == RIGHT_CLICK && type == TerminalType.RUBIX) RIGHT_CLICK else InputConstants.MOUSE_BUTTON_MIDDLE
        clickedSlots.add(slotIndex to mappedButton)
        lastClickTime = System.currentTimeMillis()

        if (simulateClick) simulateClick(slotIndex, mappedButton)
        AdsTerminalSolver.clicked(slotIndex, mappedButton, solution)
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        mc.gameMode?.handleContainerInput(player.containerMenu.containerId, slotIndex, mappedButton,
            if (mappedButton == InputConstants.MOUSE_BUTTON_MIDDLE) ContainerInput.CLONE else ContainerInput.PICKUP, player)
    }

    /** [click] for a mouse button numbered the 26.3 way (SDL: left 1, middle 2, right 3). */
    fun clickMouse(slotIndex: Int, mouseButton: Int, simulateClick: Boolean) = click(slotIndex, toContainerButton(mouseButton), simulateClick)

    open fun canClick(slotIndex: Int, button: Int): Boolean = slotIndex in solution

    fun shouldProtect(): Boolean = System.currentTimeMillis() - timeOpened < TerminalSolverSettings.firstClickProt.value

    companion object {
        const val LEFT_CLICK = 0
        const val RIGHT_CLICK = 1

        fun toContainerButton(mouseButton: Int): Int = when (mouseButton) {
            InputConstants.MOUSE_BUTTON_LEFT -> LEFT_CLICK
            InputConstants.MOUSE_BUTTON_RIGHT -> RIGHT_CLICK
            else -> mouseButton
        }

        val BLACK_PANE get() = Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK)

        /** The item has a glint set (a clicked Starts With / Select item); Odin's hasGlint. */
        fun ItemStack.hasGlint(): Boolean = components.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) != null
    }
}

class PanesHandler : TerminalHandler(TerminalType.PANES) {
    override fun solve(slots: List<Slot>, updatedIndex: Int): List<Int> {
        val red = Items.STAINED_GLASS_PANE.pick(DyeColor.RED)
        return slots.mapIndexedNotNull { index, slot -> if (slot.item.item == red) index else null }
    }

    override fun renderSlot(slotIndex: Int) = SlotLook(TerminalSolverSettings.panesColor.value.argb, null)
}

class NumbersHandler : TerminalHandler(TerminalType.NUMBERS) {
    override fun solve(slots: List<Slot>, updatedIndex: Int): List<Int> {
        val red = Items.STAINED_GLASS_PANE.pick(DyeColor.RED)
        return slots.mapIndexedNotNull { index, slot -> if (slot.item.item == red) index else null }.sortedBy { slots[it].item.count }
    }

    override fun simulateClick(slotIndex: Int, clickType: Int) {
        solution.removeAt(0)
    }

    override fun canClick(slotIndex: Int, button: Int): Boolean = slotIndex == solution.firstOrNull()

    override fun renderSlot(slotIndex: Int): SlotLook {
        val s = TerminalSolverSettings
        val solutionIndex = solution.indexOf(slotIndex)
        val color = when (solutionIndex) {
            0 -> s.numbers1Color.value.argb
            1 -> s.numbers2Color.value.argb
            2 -> s.numbers3Color.value.argb
            3 -> s.numbers4Color.value.argb
            else -> 0
        }
        return SlotLook(color, if (AdsTerminalSolver.showsNumbers()) (abs((solution.size - 10) - solutionIndex) + 1).toString() else null)
    }
}

class RubixHandler : TerminalHandler(TerminalType.RUBIX) {
    private var lockedColor: DyeColor? = null
    private val rightClickSlots = HashSet<Int>()

    override fun solve(slots: List<Slot>, updatedIndex: Int): List<Int> {
        val panes = slots.mapIndexedNotNull { index, slot -> slot.item.paneColor?.takeUnless { it == DyeColor.BLACK }?.let { index to it } }

        // The target colour is picked once, when the last pane is in (the window fills slot by slot), and kept.
        if (updatedIndex == LAST_PANE_SLOT && lockedColor == null)
            lockedColor = ORDER.minBy { goal -> clicksFor(goal, panes).values.sumOf { abs(it) } }

        val clicks = lockedColor?.let { clicksFor(it, panes) }.orEmpty()
        rightClickSlots.clear()
        clicks.forEach { (slotIndex, count) -> if (count < 0) rightClickSlots.add(slotIndex) }
        return clicks.flatMap { (slotIndex, count) -> List(abs(count)) { slotIndex } }
    }

    private fun clicksFor(goal: DyeColor, panes: List<Pair<Int, DyeColor>>): Map<Int, Int> {
        val goalIndex = ORDER.indexOf(goal)
        val leftOnly = TerminalSolverSettings.rubixMode.value == TerminalSolverSettings.RubixMode.LEFT_CLICKS_ONLY
        return panes.associate { (slotIndex, color) ->
            val forward = dist(ORDER.indexOf(color), goalIndex)
            slotIndex to if (forward > 2 && !leftOnly) forward - ORDER.size else forward
        }.filterValues { it != 0 }
    }

    private val ItemStack.paneColor: DyeColor? get() = ((item as? BlockItem)?.block as? StainedGlassPaneBlock)?.color

    override fun canClick(slotIndex: Int, button: Int): Boolean =
        slotIndex in solution && (button == RIGHT_CLICK) == (slotIndex in rightClickSlots)

    private fun dist(pane: Int, most: Int): Int = if (pane > most) (most + ORDER.size) - pane else most - pane

    override fun renderSlot(slotIndex: Int): SlotLook? {
        val s = TerminalSolverSettings
        val remaining = solution.count { it == slotIndex }.takeIf { it > 0 } ?: return null
        val clicksRequired = if (slotIndex in rightClickSlots) -remaining else remaining
        val color: TermColor = when (clicksRequired) {
            1 -> s.rubixColor1.value
            2 -> s.rubixColor2.value
            -1, 4 -> s.oppositeRubixColor1.value
            else -> s.oppositeRubixColor2.value
        }
        return SlotLook(color.argb, clicksRequired.toString())
    }

    private companion object {
        const val LAST_PANE_SLOT = 32
        val ORDER = listOf(DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE, DyeColor.RED)
    }
}

class SelectAllHandler(color: DyeColor) : TerminalHandler(TerminalType.SELECT) {
    // The 1.8 names: a black item can be an ink sac, a white one bone meal or wool, and so on.
    private val validPrefixes = when (color) {
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

    override fun solve(slots: List<Slot>, updatedIndex: Int): List<Int> =
        slots.mapIndexedNotNull { index, slot ->
            if (slot.item.item != BLACK_PANE && !slot.item.hasGlint() && validPrefixes.any(slot.item.hoverName.string.lowercase()::startsWith)) index else null
        }

    override fun renderSlot(slotIndex: Int) = SlotLook(TerminalSolverSettings.selectColor.value.argb, null)
}

class StartsWithHandler(private val letter: String) : TerminalHandler(TerminalType.STARTS_WITH) {
    /** Slots clicked: false until the server's update for it comes, then true and never offered again. */
    private val clickedOverrides = HashMap<Int, Boolean>()

    override fun solve(slots: List<Slot>, updatedIndex: Int): List<Int> {
        clickedOverrides.computeIfPresent(updatedIndex) { _, _ -> true }
        return slots.mapIndexedNotNull { index, slot ->
            if (slot.item.hoverName.string.startsWith(letter, true) && clickedOverrides[index] != true &&
                (!slot.item.hasGlint() || slot.item.item in enchantOverrides)) index else null
        }
    }

    override fun click(slotIndex: Int, button: Int, simulateClick: Boolean) {
        if (canClick(slotIndex, button) && slotIndex !in clickedOverrides) clickedOverrides[slotIndex] = false
        super.click(slotIndex, button, simulateClick)
    }

    override fun renderSlot(slotIndex: Int) = SlotLook(TerminalSolverSettings.startsWithColor.value.argb, null)

    private companion object {
        /** Items that glint of their own (enchanted golden apples, books...): their glint doesn't mean clicked. */
        val enchantOverrides by lazy { BuiltInRegistries.ITEM.filter { it.components().has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) }.toSet() + Items.GOLDEN_APPLE }
    }
}

class MelodyHandler : TerminalHandler(TerminalType.MELODY) {
    override fun solve(slots: List<Slot>, updatedIndex: Int): List<Int> {
        val magentaPane = slots.indexOfFirst { it.item.item == Items.STAINED_GLASS_PANE.pick(DyeColor.MAGENTA) }
        val greenPane = slots.indexOfLast { it.item.item == Items.STAINED_GLASS_PANE.pick(DyeColor.LIME) }
        val greenClay = slots.indexOfLast { it.item.item == Items.DYED_TERRACOTTA.pick(DyeColor.LIME) }
        return buildList {
            add(greenPane)
            add(magentaPane)
            add(magentaPane + 36)
            if (greenPane % 9 == magentaPane % 9) add(greenClay)
        }
    }

    /** The three buttons: when to press is the player's to judge. */
    override fun canClick(slotIndex: Int, button: Int): Boolean = slotIndex == 16 || slotIndex == 25 || slotIndex == 34

    override fun renderSlot(slotIndex: Int): SlotLook {
        val s = TerminalSolverSettings
        val row = slotIndex / 9
        return SlotLook(if (row == 0 || row == 4) s.melodyColumColor.value.argb else s.melodyPointerColor.value.argb, null)
    }
}
