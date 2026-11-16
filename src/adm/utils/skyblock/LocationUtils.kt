package com.adm.utils.skyblock

import com.adm.AdmMod.mc
import com.adm.events.LevelEvent
import com.adm.events.LocationChangeEvent
import com.adm.events.core.EventPriority
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.utils.equalsOneOf
import com.adm.utils.noControlCodes
import com.adm.utils.startsWithOneOf
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import kotlin.jvm.optionals.getOrNull

object LocationUtils {

    var isInSkyblock: Boolean = false
        private set

    var currentArea: Island = Island.Unknown
        private set(value) {
            field = value
            if (value !== Island.Unknown) LocationChangeEvent.postAndCatch()
        }

    var lobbyId: String? = null
        private set

    private val lobbyRegex = Regex("\\d\\d/\\d\\d/\\d\\d (\\w{0,6}) *")

    init {
        onReceive<ClientboundPlayerInfoUpdatePacket> {
            if (!isCurrentArea(Island.Unknown) || actions().none { it.equalsOneOf(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME) }) return@onReceive
            val area = entries().find { it.displayName?.string?.startsWithOneOf("Area: ", "Dungeon: ") == true }?.displayName?.string ?: return@onReceive
            currentArea = Island.entries.firstOrNull { area.contains(it.displayName, true) } ?: Island.Unknown
        }

        onReceive<ClientboundSetObjectivePacket> {
            if (!isInSkyblock) isInSkyblock = objectiveName == "SBScoreboard"
        }

        onReceive<ClientboundSetPlayerTeamPacket> (EventPriority.LOW) {
            if (!isCurrentArea(Island.Unknown)) return@onReceive
            val text = parameters.getOrNull()?.let { it.playerPrefix.string.plus(it.playerSuffix.string).noControlCodes } ?: return@onReceive

            lobbyRegex.find(text)?.groupValues?.get(1)?.let { lobbyId = it }
        }

        on<LevelEvent.Load> {
            currentArea = if (mc.hasSingleplayerServer()) Island.SinglePlayer else Island.Unknown
            isInSkyblock = false
            lobbyId = null
        }
    }

    fun isCurrentArea(vararg areas: Island): Boolean =
        currentArea == Island.SinglePlayer || areas.any { currentArea == it }
}