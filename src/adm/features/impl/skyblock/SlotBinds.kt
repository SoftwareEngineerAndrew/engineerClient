package com.adm.features.impl.skyblock

import com.mojang.blaze3d.platform.InputConstants
import com.adm.clickgui.settings.impl.*
import com.adm.events.GuiEvent
import com.adm.events.ScreenCloseEvent
import com.adm.events.ScreenEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.features.ModuleManager
import com.adm.utils.Colors
import com.adm.utils.clickSlot
import com.adm.utils.modMessage
import com.adm.utils.render.drawLine
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot

object SlotBinds : Module(
    name = "Slot Binds",
    description = "Bind slots together for quick access.",
    key = null
) {
    private val setNewSlotbind by KeybindSetting("Bind set key", InputConstants.UNKNOWN, desc = "Key to set new bindings.")
    private val lineColor by ColorSetting("Bind Color", Colors.MINECRAFT_GREEN, desc = "Color of the line drawn between slots (used in hover modes).")
    private val lineWidth by NumberSetting("Line Width", 0.5f, 0.1..2.0, 0.1, desc = "Width of the line drawn between slots.")
    private val lineDisplayMode by SelectorSetting("Line Display", LineDisplay.HOVER, desc = "When to show lines between bound slots.")
    private val currentProfile by SelectorSetting("Profile", Profile.PROFILE_1, desc = "Select which profile to use.")

    enum class LineDisplay(private val label: String) {
        HOVER("Hover"),
        ON_HOVER_SHIFT("On Hover + Shift"),
        NONE("None");

        override fun toString(): String = label
    }

    enum class Profile { PROFILE_1, PROFILE_2, PROFILE_3, PROFILE_4, PROFILE_5, PROFILE_6 }
    private val profileData by MapSetting("ProfileData", mutableMapOf<String, MutableMap<Int, Int>>())

    private const val NO_SLOT = -1

    // First slot of a bind being made, waiting for the second key press.
    private var pending = NO_SLOT

    private fun binds(): MutableMap<Int, Int> = profileData.getOrPut(currentProfile.label) { mutableMapOf() }

    // A bind is stored one way only, so a slot can be either end of it.
    private fun peekBind(slot: Int): Int {
        val map = profileData[currentProfile.label] ?: return NO_SLOT
        map[slot]?.let { return it }
        for ((from, to) in map) if (to == slot) return from
        return NO_SLOT
    }

    private fun removeBind(slot: Int): Int? {
        val map = binds()
        map.remove(slot)?.let { return it }
        val from = map.entries.firstOrNull { it.value == slot }?.key ?: return null
        map.remove(from)
        return from
    }

    private fun isHotbar(slot: Int) = slot in 36..44

    // Binds keep the player inventory screen's slot numbers; any other menu reaches the same item through its containerSlot.
    private fun bindIndex(slot: Slot): Int {
        if (slot.container !is Inventory) return NO_SLOT
        return when (val c = slot.containerSlot) {
            in 0..8 -> c + 36
            in 9..35 -> c
            in 36..39 -> 44 - c
            else -> NO_SLOT
        }
    }

    private fun menuSlot(screen: AbstractContainerScreen<*>, index: Int): Slot? {
        if (screen is InventoryScreen) return screen.menu.slots.getOrNull(index)
        for (slot in screen.menu.slots) if (bindIndex(slot) == index) return slot
        return null
    }

    private fun hovered(screen: AbstractContainerScreen<*>): Int {
        val slot = screen.hoveredSlot ?: return NO_SLOT
        return if (screen is InventoryScreen) slot.index.takeIf { it in 5..44 } ?: NO_SLOT else bindIndex(slot)
    }

    private fun slotX(screen: AbstractContainerScreen<*>, slot: Slot) = (slot.x + screen.leftPos + 8).toFloat()
    private fun slotY(screen: AbstractContainerScreen<*>, slot: Slot) = (slot.y + screen.topPos + 8).toFloat()

    init {
        on<GuiEvent.SlotClick> {
            val screen = screen as? AbstractContainerScreen<*> ?: return@on
            if (!mc.hasShiftDown()) return@on
            val clicked = hovered(screen)
            if (clicked == NO_SLOT) return@on
            val bound = peekBind(clicked)
            if (bound == NO_SLOT) return@on
            val hotbar = if (isHotbar(clicked)) clicked else if (isHotbar(bound)) bound else return@on
            val other = menuSlot(screen, if (hotbar == clicked) bound else clicked) ?: return@on
            mc.player?.clickSlot(other.index, hotbar % 36, ContainerInput.SWAP)
            cancel()
        }

        on<ScreenEvent.KeyPress> {
            val screen = screen as? AbstractContainerScreen<*> ?: return@on
            if (input.key != setNewSlotbind.value) return@on
            val slot = hovered(screen)
            if (slot == NO_SLOT) return@on
            cancel()
            val profile = currentProfile.label
            val first = pending
            if (first == NO_SLOT) {
                val removed = removeBind(slot)
                if (removed == null) {
                    pending = slot
                    return@on
                }
                ModuleManager.saveConfigurations()
                modMessage("§cRemoved bind from slot §b$slot §cto §d$removed §7($profile).")
                return@on
            }
            when {
                first == slot -> modMessage("§cYou can't bind a slot to itself.")
                !isHotbar(first) && !isHotbar(slot) -> modMessage("§cOne of the slots must be in the hotbar (36–44).")
                else -> {
                    modMessage("§aAdded bind from slot §b$first §ato §d$slot §7($profile).")
                    removeBind(first)
                    removeBind(slot)
                    binds()[first] = slot
                    ModuleManager.saveConfigurations()
                    pending = NO_SLOT
                }
            }
        }

        on<GuiEvent.DrawTooltip> {
            val screen = screen as? AbstractContainerScreen<*> ?: return@on
            val slot = hovered(screen)
            if (slot == NO_SLOT) return@on
            val from = pending
            if (from == NO_SLOT) {
                val bound = peekBind(slot)
                if (bound == NO_SLOT) return@on
                when (lineDisplayMode) {
                    LineDisplay.HOVER -> {}
                    LineDisplay.ON_HOVER_SHIFT -> if (!mc.hasShiftDown()) return@on
                    LineDisplay.NONE -> return@on
                }
                val a = screen.hoveredSlot ?: return@on
                val b = menuSlot(screen, bound) ?: return@on
                guiGraphics.drawLine(slotX(screen, a), slotY(screen, a), slotX(screen, b), slotY(screen, b), lineColor, lineWidth)
            } else {
                val a = menuSlot(screen, from) ?: return@on
                guiGraphics.drawLine(slotX(screen, a), slotY(screen, a), mouseX.toFloat(), mouseY.toFloat(), lineColor, lineWidth)
            }
        }

        on<ScreenCloseEvent> { pending = NO_SLOT }
    }
}
