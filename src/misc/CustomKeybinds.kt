package com.engineerclient.misc

import com.mojang.blaze3d.platform.InputConstants
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.KeybindSetting
import com.adm.clickgui.settings.impl.StringSetting
import com.adm.features.ModuleManager
import com.adm.features.impl.skyblock.CommandKeybinds
import com.adm.utils.sendCommand
import net.minecraft.client.Minecraft

object CustomKeybinds {
    private const val SLOTS = 50

    private class Slot(val key: KeybindSetting, val command: StringSetting) {
        fun used() = key.value != InputConstants.UNKNOWN || command.value.isNotBlank()
    }

    private val slots = ArrayList<Slot>()

    init {
        for (i in 1..SLOTS) {
            val previous = slots.lastOrNull()
            val shown = { previous == null || previous.used() }
            val command = StringSetting("Custom Command $i", "", 100, desc = "Run when Custom Key $i is pressed, with or without the slash.", placeholder = "/command")
            val key = KeybindSetting("Custom Key $i", InputConstants.UNKNOWN, "Sends Custom Command $i.").onPress { send(command.value) }
            key.withDependency(shown)
            command.withDependency(shown)
            slots += Slot(key, command)
        }
    }

    private fun send(text: String) {
        if (!CommandKeybinds.enabled || text.isBlank()) return
        if (Minecraft.getInstance().player == null) return
        sendCommand(com.engineerclient.EngineerClient.admCommand(text.trim().removePrefix("/").trim()))
    }

    fun install() {
        for (slot in slots) {
            CommandKeybinds.registerSetting(slot.key)
            CommandKeybinds.registerSetting(slot.command)
        }
        // The press cache is filled when modules register, before these exist.
        ModuleManager.keybindSettingsCache.addAll(slots.map { it.key })
    }
}
