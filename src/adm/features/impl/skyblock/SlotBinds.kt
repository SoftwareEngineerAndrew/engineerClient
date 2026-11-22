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
    description = "Bind any number of slots to a hotbar slot for quick access.",
    key = null
) {
    private val setNewSlotbind by KeybindSetting("Bind set key", InputConstants.UNKNOWN, desc = "Key to set new bindings.")
    private val lineColor by ColorSetting("Bind Color", Colors.MINECRAFT_GREEN, desc = "Color of the line drawn between slots (used in hover modes).")
    private val lineWidth by NumberSetting("Line Width", 0.5f, 0.1..2.0, 0.1, desc = "Width of the line drawn between slots.")
    private val lineDisplayMode by SelectorSetting("Line Display", LineDisplay.HOVER, desc = "When to show lines between bound slots.")
    private val currentProfile by SelectorSetting("Profile", Profile.PROFILE_1, desc = "Select which profile to use.")
    private val protectBound by BooleanSetting("Protect Bound Slots", false, desc = "Plain clicks can't move items out of bound slots.")

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

    // Set from mouse press to release, so only the click under the cursor can swap.
    private var pressed = false

    // Each slot keeps the hotbar slot it is bound to, so a hotbar slot can take any number of binds.
    private fun binds(): MutableMap<Int, Int> {
        val map = profileData.getOrPut(currentProfile.label) { mutableMapOf() }
        // Binds used to be stored in the order they were made; turn those that start on the hotbar around.
        val flip = map.entries.filter { isHotbar(it.key) && !isHotbar(it.value) && it.value !in map }
        if (flip.isNotEmpty()) {
            for ((hotbar, slot) in flip) {
                map.remove(hotbar)
                map[slot] = hotbar
            }
            ModuleManager.saveConfigurations()
        }
        return map
    }

    private fun target(slot: Int): Int = binds()[slot]?.takeIf(::isHotbar) ?: NO_SLOT

    private fun sources(hotbar: Int): List<Int> = binds().entries.filter { it.value == hotbar }.map { it.key }

    private fun isBound(slot: Int) = target(slot) != NO_SLOT || sources(slot).isNotEmpty()

    // The slot each hotbar slot last took its item from, so a click on the hotbar slot sends the item back there.
    private val takenFrom = IntArray(9) { NO_SLOT }

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

    private fun indexOf(screen: AbstractContainerScreen<*>, slot: Slot?): Int {
        if (slot == null) return NO_SLOT
        return if (screen is InventoryScreen) slot.index.takeIf { it in 5..44 } ?: NO_SLOT else bindIndex(slot)
    }

    private fun hovered(screen: AbstractContainerScreen<*>): Int = indexOf(screen, screen.hoveredSlot)

    private fun slotX(screen: AbstractContainerScreen<*>, slot: Slot) = (slot.x + screen.leftPos + 8).toFloat()
    private fun slotY(screen: AbstractContainerScreen<*>, slot: Slot) = (slot.y + screen.topPos + 8).toFloat()

    init {
        on<GuiEvent.SlotClick> {
            val screen = screen as? AbstractContainerScreen<*> ?: return@on
            val shift = mc.hasShiftDown()
            if (!shift && !protectBound) return@on
            val slot = screen.menu.slots.getOrNull(slotIndex) ?: return@on
            val clicked = indexOf(screen, slot)
            if (clicked == NO_SLOT) return@on
            if (!isBound(clicked)) return@on
            if (shift) {
                // Shift + double click sweeps every matching slot on release; those moves are not a bind press.
                if (!pressed || slot !== screen.hoveredSlot) return@on
                pressed = false
                if (swap(screen, clicked)) cancel()
                return@on
            }
            if (!slot.item.isEmpty) cancel()
        }

        on<ScreenEvent.KeyPress> {
            val screen = screen as? AbstractContainerScreen<*> ?: return@on
            if (input.key != setNewSlotbind.value) return@on
            if (bindKey(screen)) cancel()
        }

        on<ScreenEvent.MouseClick> {
            val screen = screen as? AbstractContainerScreen<*> ?: return@on
            pressed = true
            val key = setNewSlotbind
            if (key.type != InputConstants.Type.MOUSE || click.button() != key.value) return@on
            if (bindKey(screen)) cancel()
        }

        on<GuiEvent.DrawTooltip> {
            val screen = screen as? AbstractContainerScreen<*> ?: return@on
            val slot = hovered(screen)
            if (slot == NO_SLOT) return@on
            val from = pending
            if (from == NO_SLOT) {
                when (lineDisplayMode) {
                    LineDisplay.HOVER -> {}
                    LineDisplay.ON_HOVER_SHIFT -> if (!mc.hasShiftDown()) return@on
                    LineDisplay.NONE -> return@on
                }
                val a = screen.hoveredSlot ?: return@on
                val to = target(slot)
                val ends = if (to != NO_SLOT) listOf(to) else sources(slot)
                for (end in ends) {
                    val b = menuSlot(screen, end) ?: continue
                    guiGraphics.drawLine(slotX(screen, a), slotY(screen, a), slotX(screen, b), slotY(screen, b), lineColor, lineWidth)
                }
            } else {
                val a = menuSlot(screen, from) ?: return@on
                guiGraphics.drawLine(slotX(screen, a), slotY(screen, a), mouseX.toFloat(), mouseY.toFloat(), lineColor, lineWidth)
            }
        }

        on<ScreenEvent.MouseRelease> { pressed = false }

        on<ScreenCloseEvent> { pending = NO_SLOT }
    }

    private fun swap(screen: AbstractContainerScreen<*>, clicked: Int): Boolean {
        val to = target(clicked)
        val hotbar = if (to != NO_SLOT) to else clicked
        val other = if (to != NO_SLOT) clicked else {
            val from = sources(clicked)
            if (from.isEmpty()) return false
            takenFrom[hotbar - 36].takeIf { it in from } ?: from.first()
        }
        val slot = menuSlot(screen, other) ?: return false
        if (to != NO_SLOT) takenFrom[hotbar - 36] = clicked
        mc.player?.clickSlot(slot.index, hotbar % 36, ContainerInput.SWAP)
        return true
    }

    private fun bindKey(screen: AbstractContainerScreen<*>): Boolean {
        val slot = hovered(screen)
        if (slot == NO_SLOT) return false
        val profile = currentProfile.label
        val first = pending
        if (first == NO_SLOT) {
            val removed = binds().remove(slot)
            if (removed == null) {
                pending = slot
                return true
            }
            ModuleManager.saveConfigurations()
            modMessage("§cRemoved bind from slot §b$slot §cto §d$removed §7($profile).")
            return true
        }
        when {
            first == slot -> modMessage("§cYou can't bind a slot to itself.")
            !isHotbar(first) && !isHotbar(slot) -> modMessage("§cOne of the slots must be in the hotbar (36–44).")
            else -> {
                val (from, to) = if (isHotbar(first) && !isHotbar(slot)) slot to first else first to slot
                modMessage("§aAdded bind from slot §b$from §ato §d$to §7($profile).")
                binds()[from] = to
                ModuleManager.saveConfigurations()
                pending = NO_SLOT
            }
        }
        return true
    }

}
