package com.engineerclient.terminals.sim

import com.adm.utils.hasGlint
import com.adm.utils.modMessage
import com.engineerclient.terminals.TerminalTypes
import com.engineerclient.terminals.TerminalUtils
import net.minecraft.core.component.DataComponents
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.random.Random

class SelectAllSim(private val color: DyeColor = DyeColor.entries.random()) : TermSimGUI(
    "Select all the ${color.name.replace("LIGHT_GRAY", "SILVER").replace('_', ' ')} items!",
    TerminalTypes.SELECT.windowSize
) {
    private val wanted = itemsOf(color)

    override fun create() {
        val sure = cells.random()
        setSlots {
            when {
                it.index !in cells -> blackPane
                it.index == sure || Math.random() > 0.75 -> ItemStack(wanted.random())
                else -> ItemStack(itemsOf(otherColor()).random())
            }
        }
    }

    private fun otherColor(): DyeColor {
        val pick = Random.nextInt(DyeColor.entries.size - 1)
        return DyeColor.entries[if (pick >= color.ordinal) pick + 1 else pick]
    }

    override fun slotClick(slot: Slot, button: Int) {
        val stack = slot.item
        if (stack.item !in wanted) return modMessage("§cThat item is not: ${color.name}!")
        if (stack.hasGlint()) return modMessage("§cAlready selected!")
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        slot.setSlot(stack)
        if (guiInventorySlots.none { !it.item.hasGlint() && it.item.item in wanted }) TerminalUtils.lastTermOpened?.onComplete()
        super.slotClick(slot, button)
    }

    private companion object {
        val cells = (1..4).flatMap { row -> (1..7).map { row * 9 + it } }.toSet()

        // Glass, wool, concrete and the dye, with the dyes Hypixel still shows as their old items.
        val byColor: Map<DyeColor, List<Item>> = DyeColor.entries.associateWith { c ->
            val dye = when (c) {
                DyeColor.WHITE -> Items.BONE_MEAL
                DyeColor.BLUE -> Items.LAPIS_LAZULI
                DyeColor.BLACK -> Items.INK_SAC
                DyeColor.BROWN -> Items.COCOA_BEANS
                else -> Items.DYE.pick(c)
            }
            listOf(Items.STAINED_GLASS.pick(c), Items.WOOL.pick(c), Items.CONCRETE.pick(c), dye)
        }

        fun itemsOf(c: DyeColor) = byColor.getValue(c)
    }
}
