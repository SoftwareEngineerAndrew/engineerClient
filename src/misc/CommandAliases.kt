package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents

object CommandAliases : Module(
    name = "Command Aliases",
    category = Category.SKYBLOCK,
    description = "Your own short commands: /alias runs its command, with anything typed after it.",
    key = null,
) {
    private const val SLOTS = 20

    private class Slot(val alias: StringSetting, val command: StringSetting) {
        fun used() = alias.value.isNotBlank() || command.value.isNotBlank()
    }

    private val slots = ArrayList<Slot>()
    private var running = false

    init {
        for (i in 1..SLOTS) {
            val previous = slots.lastOrNull()
            val shown = { previous == null || previous.used() }
            val alias = StringSetting("Alias $i", "", 32, desc = "What you type, without the slash.", placeholder = "d")
            val command = StringSetting("Command $i", "", 200, desc = "What Alias $i runs.", placeholder = "warp dungeon_hub")
            registerSetting(alias.withDependency(shown))
            registerSetting(command.withDependency(shown))
            slots += Slot(alias, command)
        }

        ClientSendMessageEvents.ALLOW_COMMAND.register { typed -> !(enabled && !running && run(typed)) }
    }

    private fun run(typed: String): Boolean {
        val word = typed.substringBefore(' ')
        val rest = typed.substringAfter(' ', "")
        val slot = slots.firstOrNull { it.alias.value.trim().removePrefix("/").equals(word, ignoreCase = true) && it.command.value.isNotBlank() } ?: return false
        val target = slot.command.value.trim().removePrefix("/")
        // An alias whose command is itself an alias would loop.
        running = true
        try { mc.player?.connection?.sendCommand(if (rest.isBlank()) target else "$target $rest") } finally { running = false }
        return true
    }
}
