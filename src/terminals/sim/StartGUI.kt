package com.engineerclient.terminals.sim

import com.adm.utils.handlers.schedule
import com.adm.utils.modMessage
import com.adm.utils.toFixed
import com.engineerclient.terminals.TermSimulator
import com.engineerclient.terminals.TerminalTypes
import com.engineerclient.terminals.Terminals
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

object StartGUI : TermSimGUI("Terminal Simulator", 27) {
    private const val RESET_SLOT = 4
    private const val RANDOM_SLOT = 13
    private const val CONFIRM_TICKS = 60

    // Slot to terminal; the random button sits in the middle of them.
    private val menuSlots = intArrayOf(10, 11, 12, 14, 15, 16)
    private val menuTypes = arrayOf(TerminalTypes.PANES, TerminalTypes.RUBIX, TerminalTypes.NUMBERS, TerminalTypes.STARTS_WITH, TerminalTypes.SELECT, TerminalTypes.MELODY)
    private val menuIcons: Array<Item> = Items.DYE.let { arrayOf(it.lime(), it.red(), it.cyan(), it.pink(), it.brown(), it.purple()) }
    private val menuColors = arrayOf("§a", "§6", "§3", "§5", "§b", "§d")

    private val resetButton = named(Items.DYE.black(), "§cReset PBs!")
    private val randomButton = named(Items.DYE.white(), "§7Random")

    private var confirming = false

    private fun named(item: Item, name: String) = ItemStack(item).apply { set(DataComponents.CUSTOM_NAME, Component.literal(name)) }

    fun getTermPB(type: TerminalTypes): List<Component> {
        val pb = Terminals.termSimPBs.get(type.name)
        return listOf(Component.literal(if (pb == null) "§7No PB set!" else "§7Personal Best: §d${pb.toFixed()}s"))
    }

    private fun menuIcon(at: Int) = named(menuIcons[at], menuColors[at] + menuTypes[at].termName).apply {
        set(DataComponents.LORE, ItemLore(getTermPB(menuTypes[at])))
    }

    override fun create() {
        setSlots {
            when (val i = it.index) {
                RESET_SLOT -> resetButton
                RANDOM_SLOT -> randomButton
                else -> menuSlots.indexOf(i).let { at -> if (at < 0) blackPane else menuIcon(at) }
            }
        }
    }

    override fun slotClick(slot: Slot, button: Int) {
        when (val i = slot.index) {
            RESET_SLOT -> resetClicked()
            RANDOM_SLOT -> TermSimulator.openRandomTerminal(ping)
            else -> {
                val at = menuSlots.indexOf(i)
                if (at >= 0) menuTypes[at].simulator().open(ping)
            }
        }
    }

    private fun resetClicked() {
        if (!confirming) {
            modMessage("§cAre you sure you want to reset your PBs? Click again to confirm.")
            confirming = true
            schedule(CONFIRM_TICKS) {
                if (!confirming) return@schedule
                modMessage("§aPBs reset cancelled.")
                confirming = false
            }
            return
        }
        Terminals.termSimPBs.reset()
        create()
        modMessage("§aPBs reset!")
        confirming = false
    }
}
