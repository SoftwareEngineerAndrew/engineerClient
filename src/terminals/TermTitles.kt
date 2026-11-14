package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.events.PacketEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.utils.noControlCodes
import com.odtheking.odin.utils.setSubtitle
import com.odtheking.odin.utils.setTitle
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket

/** Hypixel's terminal, lever and device titles in your own format. */
object TermTitles {
    private val titleRegex = Regex("^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d+)/(\\d+)\\)$")
    private val colorCode = Regex("&([0-9A-FK-ORa-fk-or])")

    /** Titles is on: Coffee Client's Term Info clears what this puts up for titles it hides. */
    @JvmStatic val on get() = Terminals.titlesOn

    fun install() {
        onReceive<ClientboundSetTitleTextPacket> { it.handle(text.string) }
        onReceive<ClientboundSetSubtitleTextPacket> { it.handle(text.string) }
        EventBus.subscribe(this)
    }

    private fun PacketEvent.Receive.handle(text: String) {
        if (!Terminals.titlesOn) return
        val (name, action, type, current, total) = titleRegex.find(text.noControlCodes)?.destructured ?: return
        if (Terminals.selfOnly && name != mc.player?.name?.string) return
        val replace = when (type) {
            "terminal" -> Terminals.replaceTerminal
            "lever" -> Terminals.replaceLever
            "device" -> Terminals.replaceDevice
            else -> false
        }
        if (!replace) return
        cancel()
        mc.execute {
            val title = Terminals.customTitle
                .replace("{name}", name).replace("{action}", action).replace("{type}", type)
                .replace("{current}", current).replace("{total}", total)
                .replace(colorCode, "§$1").ifEmpty { return@execute }
            if (Terminals.useSubtitle) setSubtitle(title) else setTitle(title)
        }
    }
}
