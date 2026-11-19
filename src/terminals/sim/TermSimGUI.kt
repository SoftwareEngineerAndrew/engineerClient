package com.engineerclient.terminals.sim

import com.adm.AdmMod.mc
import com.adm.events.GuiEvent
import com.adm.events.ScreenCloseEvent
import com.adm.utils.handlers.schedule
import com.adm.utils.playSoundAtPlayer
import com.engineerclient.terminals.TermSounds
import com.engineerclient.terminals.TerminalEvent
import com.engineerclient.terminals.TerminalHandler
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.PlayerEquipment
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** An item with an empty name, as the panes and clay in a terminal are. */
internal fun unnamed(item: Item, count: Int = 1): ItemStack = ItemStack(item, count).apply { set(DataComponents.CUSTOM_NAME, Component.literal("")) }

private val chestTypes = arrayOf(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6)

private fun emptyInventory(): Inventory {
    val player = mc.player!!
    return Inventory(player, PlayerEquipment(player))
}

/** A terminal window that lives only on this client; clicks wait the simulated ping, then [slotClick] answers them. */
open class TermSimGUI private constructor(val name: String, val size: Int, playerInv: Inventory) : ContainerScreen(
    ChestMenu(chestTypes[((size + 8) / 9 - 1).coerceIn(0, 5)], 0, playerInv, SimpleContainer(size), size / 9),
    playerInv,
    Component.literal(name)
) {
    constructor(name: String, size: Int) : this(name, size, emptyInventory())

    val blackPane = unnamed(Items.STAINED_GLASS_PANE.black())
    protected val guiInventorySlots: List<Slot> get() = menu.slots.subList(0, size)
    protected var ping = 0L

    open fun create() {
        setSlots { blackPane }
    }

    fun open(terminalPing: Long = 0L) {
        mc.setScreenAndShow(this)
        create()
        ping = terminalPing
    }

    open fun slotClick(slot: Slot, button: Int) {
        playTermSimSound()
    }

    fun TerminalHandler.onComplete() {
        ScreenCloseEvent.postAndCatch()
        TerminalEvent.Solve(this).postAndCatch()
        StartGUI.open(ping)
    }

    override fun slotClicked(slot: Slot, i: Int, j: Int, clickType: ContainerInput) {
        if (GuiEvent.SlotClick(this, i, j).postAndCatch()) return
        @Suppress("SENSELESS_COMPARISON")
        if (slot != null) answerLater(slot, j)
    }

    fun clickIndex(index: Int, button: Int) {
        if (index in 0 until size) answerLater(menu.slots[index], button)
    }

    private fun answerLater(slot: Slot, button: Int) {
        if (mc.gui.screen() is StartGUI) return slotClick(slot, button)
        if (slot.container !== menu.container || slot.item.`is`(blackPane.item)) return
        if (ping <= 0L) return slotClick(slot, button)
        schedule((ping / 50).toInt()) { if (mc.gui.screen() === this) slotClick(slot, button) }
    }

    protected fun setSlots(block: (Slot) -> ItemStack) {
        for (i in 0 until size) menu.slots[i].setSlot(block(menu.slots[i]))
    }

    protected fun Slot.setSlot(stack: ItemStack) {
        menu.setItem(index, menu.incrementStateId(), stack)
    }

    protected fun playTermSimSound() {
        if (!TermSounds.replacesClicks) playSoundAtPlayer(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1f)
    }
}
