package com.devgineerclient.misc

import com.odtheking.odin.utils.sendCommand
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal

/** /edt: says #EndDungeonsToxicity in party chat. */
object Edt {
    fun install() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("edt").executes { sendCommand("pc #EndDungeonsToxicity"); 1 })
        }
    }
}
