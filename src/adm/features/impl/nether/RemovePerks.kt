package com.adm.features.impl.nether

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.GuiEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.containsOneOf
import com.adm.utils.equalsOneOf
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

object RemovePerks : Module(
    name = "Remove Perks",
    description = "Removes certain perks from the perk menu."
) {
    private val renderStun by BooleanSetting("Show Stun", false, desc = "Shows the stun role perks.")

    init {
        on<GuiEvent.RenderSlot> {
            if (screen.title.string == "Perk Menu" && slotCheck(slot.item.hoverName.string))
                cancel()
        }

        on<GuiEvent.SlotClick> {
            if (screen is AbstractContainerScreen<*> && screen.title.string == "Perk Menu" && slotCheck(screen.menu.getSlot(slotIndex).item.hoverName.string))
                cancel()
        }
    }

    private fun slotCheck(slot: String): Boolean =
        slot.containsOneOf("Steady Hands", "Bomberman", "Mining Frenzy") ||
        slot.equalsOneOf("Elle's Lava Rod", "Elle's Pickaxe", "Auto Revive") ||
        (!renderStun && slot.containsOneOf("Human Cannonball"))
}