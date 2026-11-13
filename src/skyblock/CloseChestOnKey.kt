package com.engineerclient.skyblock

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

object CloseChestOnKey : Module(
    name = "Close Chest On Key",
    category = Category.DUNGEON,
    description = "Closes a secret chest when you press a movement or sneak key.",
    key = null,
) {
    private val inBoss by BooleanSetting("In Boss", false, desc = "Also closes chests during the boss fight.")

    init {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            ScreenKeyboardEvents.allowKeyPress(screen).register { s, key ->
                if (!enabled || !DungeonUtils.inDungeons || (DungeonUtils.inBoss && !inBoss)) return@register true
                val title = s.title.string
                if (title != "Chest" && title != "Large Chest") return@register true
                val o = Minecraft.getInstance().options
                if (listOf(o.keyUp, o.keyLeft, o.keyDown, o.keyRight, o.keyShift).none { it.matches(key) }) return@register true
                Minecraft.getInstance().player?.closeContainer()
                false
            }
        }
    }
}
