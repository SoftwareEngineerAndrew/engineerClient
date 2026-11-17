package com.engineerclient.skyblock

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.features.impl.dungeon.SecretClicked
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent

/** Secrets' Chests section. */
object CloseChestOnKey {
    private val dropdown = DropdownSetting("Chests", desc = "Closing secret chests.")
    private val on = BooleanSetting("Close Chest On Key", false, desc = "Closes a secret chest when you press a movement or sneak key.").withDependency { dropdown.value }

    private val active get() = SecretClicked.enabled && on.value && DungeonUtils.inDungeons

    private val closeOnMove = ScreenKeyboardEvents.AllowKeyPress { _, key -> !(active && isMoveKey(key) && close()) }

    fun install() {
        SecretClicked.registerSetting(dropdown)
        SecretClicked.registerSetting(on)
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is AbstractContainerScreen<*> && isChest(screen)) ScreenKeyboardEvents.allowKeyPress(screen).register(closeOnMove)
        }
    }

    private fun isChest(screen: Screen): Boolean {
        val title = screen.title.string
        return title == "Chest" || title == "Large Chest"
    }

    private fun isMoveKey(key: KeyEvent): Boolean {
        val o = Minecraft.getInstance().options
        return o.keyUp.matches(key) || o.keyLeft.matches(key) || o.keyDown.matches(key) || o.keyRight.matches(key) || o.keyShift.matches(key)
    }

    private fun close(): Boolean {
        Minecraft.getInstance().player?.closeContainer()
        return true
    }
}
