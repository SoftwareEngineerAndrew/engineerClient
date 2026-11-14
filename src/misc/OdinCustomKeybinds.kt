package com.engineerclient.misc

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.skyblock.CommandKeybinds
import com.odtheking.odin.utils.sendCommand
import net.minecraft.client.Minecraft

object OdinCustomKeybinds {
    private const val SLOTS = 20

    private class Slot(val key: KeybindSetting, val command: StringSetting) {
        fun used() = key.value != InputConstants.UNKNOWN || command.value.isNotBlank()
    }

    private val slots = ArrayList<Slot>()

    init {
        for (i in 1..SLOTS) {
            val previous = slots.lastOrNull()
            val shown = { previous == null || previous.used() }
            val command = StringSetting("Custom Command $i", "", 100, desc = "Sent when Custom Key $i is pressed; no slash sends it as chat.", placeholder = "/command")
            val key = KeybindSetting("Custom Key $i", InputConstants.UNKNOWN, "Sends Custom Command $i.").onPress { send(command.value) }
            key.withDependency(shown)
            command.withDependency(shown)
            slots += Slot(key, command)
        }
    }

    private fun send(text: String) {
        if (!CommandKeybinds.enabled || text.isBlank()) return
        val connection = Minecraft.getInstance().player?.connection ?: return
        if (text.startsWith("/")) sendCommand(text.substring(1).trim()) else connection.sendChat(text)
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
