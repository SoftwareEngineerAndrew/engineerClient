package com.adm.features.impl.nether

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.GuiEvent
import com.adm.events.core.on
import com.adm.features.Module
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component

object RemovePerks : Module(
    name = "Remove Perks",
    description = "Removes certain perks from the perk menu."
) {
    private val renderStun by BooleanSetting("Show Stun", false, desc = "Shows the stun role perks.")

    private const val KEEP: Byte = 0
    private const val HIDE: Byte = 1
    private const val STUN: Byte = 2

    private var titleSeen: Component? = null
    private var isPerkMenu = false

    private var seenNames = arrayOfNulls<Component>(64)
    private var kinds = ByteArray(64)

    init {
        on<GuiEvent.RenderSlot> {
            if (!perkMenu(screen)) return@on
            if (hidden(slot.index, slot.item.hoverName)) cancel()
        }

        on<GuiEvent.SlotClick> {
            if (screen !is AbstractContainerScreen<*> || !perkMenu(screen)) return@on
            if (slotIndex !in 0 until screen.menu.slots.size) return@on
            if (hidden(-1, screen.menu.getSlot(slotIndex).item.hoverName)) cancel()
        }
    }

    private fun perkMenu(screen: Screen): Boolean {
        val title = screen.title
        if (title !== titleSeen) {
            titleSeen = title
            isPerkMenu = title.string == "Perk Menu"
        }
        return isPerkMenu
    }

    private fun hidden(index: Int, name: Component): Boolean {
        val kind = if (index < 0) kindOf(name.string) else {
            if (index >= kinds.size) {
                seenNames = seenNames.copyOf(index + 1)
                kinds = kinds.copyOf(index + 1)
            }
            if (seenNames[index] !== name) {
                seenNames[index] = name
                kinds[index] = kindOf(name.string)
            }
            kinds[index]
        }
        return kind == HIDE || (kind == STUN && !renderStun)
    }

    private fun kindOf(name: String): Byte = when {
        "Steady Hands" in name || "Bomberman" in name || "Mining Frenzy" in name -> HIDE
        name == "Elle's Lava Rod" || name == "Elle's Pickaxe" || name == "Auto Revive" -> HIDE
        "Human Cannonball" in name -> STUN
        else -> KEEP
    }
}
