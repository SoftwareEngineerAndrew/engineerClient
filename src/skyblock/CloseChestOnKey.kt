package com.engineerclient.skyblock

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.features.impl.dungeon.SecretClicked
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/** Secrets' Chests section. */
object CloseChestOnKey {
    private val dropdown = DropdownSetting("Chests", desc = "Closing secret chests.")
    private val on = BooleanSetting("Close Chest On Key", false, desc = "Closes a secret chest when you press a movement or sneak key.").withDependency { dropdown.value }

    fun install() {
        SecretClicked.registerSetting(dropdown)
        SecretClicked.registerSetting(on)
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            ScreenKeyboardEvents.allowKeyPress(screen).register { s, key ->
                if (!SecretClicked.enabled || !on.value || !DungeonUtils.inDungeons) return@register true
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
