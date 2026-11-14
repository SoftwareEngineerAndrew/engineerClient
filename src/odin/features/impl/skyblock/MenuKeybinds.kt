package com.odtheking.odin.features.impl.skyblock

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.clickSlot
import com.odtheking.odin.utils.loreString
import com.odtheking.odin.utils.modMessage
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

object MenuKeybinds : Module(
    name = "Menu Keybinds",
    description = "Keybinds for the wardrobe, loadout and pets menus.",
    key = null
) {
    private val wardrobeNext by KeybindSetting("Wardrobe Next Page", InputConstants.KEY_RIGHT, desc = "Next wardrobe page.")
    private val wardrobePrevious by KeybindSetting("Wardrobe Previous Page", InputConstants.KEY_LEFT, desc = "Previous wardrobe page.")
    private val wardrobeUnequip by KeybindSetting("Wardrobe Unequip", InputConstants.UNKNOWN, desc = "Unequips the equipped wardrobe set.")
    private val wardrobeNoUnequip by BooleanSetting("Wardrobe Disable Unequip", desc = "Prevents unequipping the equipped set.")
    private val wardrobeAdvanced by DropdownSetting("Wardrobe Slots", desc = "Shows keybinds to equip each wardrobe slot.")
    private val wardrobeKeys = (1..9).map { i ->
        KeybindSetting("Wardrobe $i", InputConstants.KEY_1 + i - 1, desc = "Equips wardrobe slot $i.").withDependency { wardrobeAdvanced }.also { registerSetting(it) }
    }

    private val loadoutNext by KeybindSetting("Loadout Next Page", InputConstants.KEY_RIGHT, desc = "Next loadout page.")
    private val loadoutPrevious by KeybindSetting("Loadout Previous Page", InputConstants.KEY_LEFT, desc = "Previous loadout page.")
    private val loadoutAdvanced by DropdownSetting("Loadout Slots", desc = "Shows keybinds to equip each loadout slot.")
    private val loadoutKeys = listOf(
        InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3, InputConstants.KEY_4, InputConstants.KEY_5, InputConstants.KEY_6,
        InputConstants.KEY_7, InputConstants.KEY_8, InputConstants.KEY_9, InputConstants.KEY_0, InputConstants.KEY_MINUS, InputConstants.KEY_EQUALS
    ).mapIndexed { i, default ->
        KeybindSetting("Loadout ${i + 1}", default, desc = "Equips loadout slot ${i + 1}.").withDependency { loadoutAdvanced }.also { registerSetting(it) }
    }

    private val petUnequip by KeybindSetting("Pet Unequip", InputConstants.UNKNOWN, desc = "Unequips the current pet.")
    private val petNext by KeybindSetting("Pet Next Page", InputConstants.UNKNOWN, desc = "Next pets page.")
    private val petPrevious by KeybindSetting("Pet Previous Page", InputConstants.UNKNOWN, desc = "Previous pets page.")
    private val petNoUnequip by BooleanSetting("Pet Disable Unequip", false, desc = "Pet keybinds never unequip a pet.")
    private val closeIfAlreadyEquipped by BooleanSetting("Close If Equipped", false, desc = "Closes the pets menu if the pet is already equipped.")
    private val petAdvanced by DropdownSetting("Pet Slots", desc = "Shows keybinds to equip each pet.")
    private val petKeys = (1..9).map { i ->
        KeybindSetting("Pet $i", InputConstants.KEY_1 + i - 1, desc = "Pet $i on the list.").withDependency { petAdvanced }.also { registerSetting(it) }
    }

    private val wardrobeRegex = Regex("\\((\\d)/(\\d)\\) (Armor|Equipment) Sets")
    private val equippedRegex = Regex("Slot (\\d): Equipped")
    private val loadoutRegex = Regex("\\((\\d)/(\\d)\\) Loadout")
    private val loadoutSlots = intArrayOf(14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43)
    private val petSlots = intArrayOf(10, 11, 12, 13, 14, 15, 16, 19, 20)
    private val petsRegex = Regex("(?:\\((\\d)/(\\d)\\)\\s*)?Pets")

    init {
        on<ScreenEvent.MouseClick> {
            if (screen is AbstractContainerScreen<*> && onClick(screen, InputConstants.Type.MOUSE.getOrCreate(click.button()))) cancel()
        }

        on<ScreenEvent.KeyPress> {
            if (screen is AbstractContainerScreen<*> && onClick(screen, InputConstants.getKey(input))) cancel()
        }
    }

    private fun onClick(screen: AbstractContainerScreen<*>, key: InputConstants.Key): Boolean {
        val title = screen.title.string
        val index = when {
            wardrobeRegex.containsMatchIn(title) -> wardrobe(screen, title, key)
            loadoutRegex.containsMatchIn(title) -> loadout(title, key)
            petsRegex.containsMatchIn(title) -> pet(screen, title, key)
            else -> null
        } ?: return false
        mc.player?.clickSlot(index)
        return true
    }

    private fun wardrobe(screen: AbstractContainerScreen<*>, title: String, key: InputConstants.Key): Int? {
        val (current, total) = wardrobeRegex.find(title)?.destructured?.let {
            it.component1().toIntOrNull() to it.component2().toIntOrNull()
        } ?: return null
        if (current == null || total == null) return null

        val equippedIndex = screen.menu.slots.find { equippedRegex.matches(it.item.hoverName.string) }?.index

        val index = when (key) {
            wardrobeNext -> if (current < total) 53 else return null
            wardrobePrevious -> if (current > 1) 45 else return null
            wardrobeUnequip -> equippedIndex ?: return null
            else -> {
                val keyIndex = wardrobeKeys.indexOfFirst { it.value == key }.takeIf { it != -1 } ?: return null
                if (equippedIndex == keyIndex + 36 && wardrobeNoUnequip) return modMessage("§cSet already equipped.").let { null }
                keyIndex + 36
            }
        }
        if (wardrobeNoUnequip && screen.menu.slots[index].item.isEmpty) return null
        return index
    }

    private fun loadout(title: String, key: InputConstants.Key): Int? {
        val (current, total) = loadoutRegex.find(title)?.destructured?.let {
            it.component1().toIntOrNull() to it.component2().toIntOrNull()
        } ?: return null
        if (current == null || total == null) return null

        return when (key) {
            loadoutNext -> if (current < total) 44 else null
            loadoutPrevious -> if (current > 1) 17 else null
            else -> loadoutSlots.getOrNull(loadoutKeys.indexOfFirst { it.value == key })
        }
    }

    private fun pet(screen: AbstractContainerScreen<*>, title: String, key: InputConstants.Key): Int? {
        val (current, total) = petsRegex.find(title)?.destructured?.let {
            (it.component1().toIntOrNull() ?: 1) to (it.component2().toIntOrNull() ?: 1)
        } ?: return null

        var index = when (key) {
            petNext -> if (current < total) 53 else return null.also { modMessage("§cYou are already on the last page.") }
            petPrevious -> if (current > 1) 45 else return null.also { modMessage("§cYou are already on the first page.") }
            petUnequip ->
                screen.menu.slots.subList(10, 43)
                    .indexOfFirst { it.item.loreString.contains("Click to despawn!") }
                    .takeIf { it != -1 }?.plus(10) ?: return null.also { modMessage("§cCouldn't find equipped pet") }

            else -> petSlots.getOrNull(petKeys.indexOfFirst { it.value == key }) ?: return null
        }

        if (screen.menu.slots[index].item.loreString.contains("Click to despawn!") && key != petUnequip) {
            modMessage("§cThat pet is already equipped!")
            if (closeIfAlreadyEquipped) index = 49
            else if (petNoUnequip) return null
        }
        return index
    }
}
