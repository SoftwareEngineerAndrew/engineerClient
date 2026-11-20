package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.adm.events.PacketEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.onReceive
import com.adm.utils.noControlCodes
import com.adm.utils.setSubtitle
import com.adm.utils.setTitle
import com.adm.utils.skyblock.dungeon.DungeonClass
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket

/** Hypixel's terminal, lever, device and gate titles in your own format. */
object TermTitles {
    private val titleRegex = Regex("^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d+)/(\\d+)\\)$")
    private val gateDestroyed = Regex("^The gate has been destroyed!$")
    private val gateOpens = Regex("^The gate will open in (\\d+) seconds?!$")
    private val colorCode = Regex("&([0-9A-FK-ORa-fk-or])")

    fun install() {
        onReceive<ClientboundSetTitleTextPacket> { it.handle(text.string) }
        onReceive<ClientboundSetSubtitleTextPacket> { it.handle(text.string) }
        SubscribeWhile.add(this) { Terminals.titlesOn }
    }

    private fun PacketEvent.Receive.handle(text: String) {
        val plain = text.noControlCodes
        if (Terminals.replaceGate) {
            if (gateDestroyed.matches(plain)) return show("§aGate destroyed!")
            gateOpens.find(plain)?.let { return show("§aGate opens in §f${it.groupValues[1]}s") }
        }
        val (name, action, type, current, total) = titleRegex.find(plain)?.destructured ?: return
        if (Terminals.phaseDone && current == total) return show("§aPhase Done!")
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
            // Teammates change on the game thread.
            val clazz = DungeonUtils.dungeonTeammates.firstOrNull { it.name == name }?.clazz?.takeIf { it != DungeonClass.EMPTY }
            val cur = if (Terminals.progressColor) progressColor(current.toIntOrNull() ?: 0, total.toIntOrNull() ?: 0) + current else current
            val title = Terminals.customTitle
                .replace("{name}", name).replace("{action}", action).replace("{type}", type)
                .replace("{current}", cur).replace("{total}", total)
                .replace("{class}", clazz?.let { "§${it.colorCode}${it.name.lowercase().replaceFirstChar(Char::uppercase)}" } ?: "")
                .replace(colorCode, "§$1").ifEmpty { return@execute }
            if (Terminals.useSubtitle) setSubtitle(title) else setTitle(title)
        }
    }

    private fun progressColor(current: Int, total: Int) = when {
        current >= total -> "§a"
        current * 2 >= total -> "§e"
        else -> "§c"
    }

    private fun PacketEvent.Receive.show(title: String) {
        cancel()
        mc.execute { if (Terminals.useSubtitle) setSubtitle(title) else setTitle(title) }
    }
}
