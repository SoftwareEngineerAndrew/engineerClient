package com.adm.features.impl.skyblock

import com.mojang.blaze3d.platform.InputConstants
import com.adm.clickgui.settings.impl.KeybindSetting
import com.adm.features.Module
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.LocationUtils

object CommandKeybinds : Module(
    name = "Command Keybinds",
    description = "Various keybinds for common skyblock commands.",
    key = null
) {
    private fun bind(name: String, desc: String, command: String) =
        KeybindSetting(name, InputConstants.UNKNOWN, desc = desc).onPress {
            if (enabled && LocationUtils.isInSkyblock) sendCommand(command)
        }

    private val pets by bind("Pets", "Opens the pets menu.", "pets")
    private val storage by bind("Storage", "Opens the storage menu.", "storage")
    private val armorSets by bind("Armor Wardrobe", "Opens the armor wardrobe menu.", "armor")
    private val equipmentSets by bind("Equip Wardrobe", "Opens the equipment wardrobe menu.", "equipment")
    private val loadouts by bind("Loadouts", "Opens the loadouts menu.", "loadout")
    private val stats by bind("Stats", "Opens the stats menu.", "stats")
    private val dhub by bind("Dungeon Hub", "Warps to the dungeon hub.", "warp dungeon_hub")
    private val potion by bind("Potion Bag", "Open the potion bag.", "potionbag")
}
