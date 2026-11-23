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
import net.hypixel.data.type.GameType
import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket
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

    // A location sent while switching servers (no player yet) belongs to the world that is about to load.
    private var pendingArea: Island? = null
    private var pendingSkyblock = false
    private var pendingKnown = false
    // The Mod API has told where this world is: the tab list needn't be read for it.
    @Volatile private var apiKnown = false

    init {
        HypixelModAPI.getInstance().subscribeToEventPacket(ClientboundLocationPacket::class.java)
        HypixelModAPI.getInstance().createHandler(ClientboundLocationPacket::class.java) { packet ->
            val skyblock = packet.serverType.getOrNull() == GameType.SKYBLOCK
            val area = if (skyblock) packet.map.getOrNull()?.let(::islandFromMap) ?: Island.Unknown else Island.Unknown
            val known = !skyblock || area !== Island.Unknown
            if (mc.player == null) {
                pendingArea = area
                pendingSkyblock = skyblock
                pendingKnown = known
                return@createHandler
            }
            if (known) apiKnown = true
            if (skyblock) isInSkyblock = true
            if (area !== Island.Unknown && currentArea !== area) currentArea = area
        }

        onReceive<ClientboundPlayerInfoUpdatePacket> {
            if (apiKnown || !isCurrentArea(Island.Unknown) || actions().none { it.equalsOneOf(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME) }) return@onReceive
            val area = entries().find { it.displayName?.string?.startsWithOneOf("Area: ", "Dungeon: ") == true }?.displayName?.string ?: return@onReceive
            currentArea = Island.entries.firstOrNull { area.contains(it.displayName, true) } ?: Island.Unknown
        }

        onReceive<ClientboundSetObjectivePacket> {
            if (!isInSkyblock) isInSkyblock = objectiveName == "SBScoreboard"
        }

        onReceive<ClientboundSetPlayerTeamPacket> (EventPriority.LOW) {
            // Not tied to the area: the Mod API usually knows it before the sidebar comes, and the lobby id still has to be read.
            if (lobbyId != null) return@onReceive
            val text = parameters.getOrNull()?.let { it.playerPrefix.string.plus(it.playerSuffix.string).noControlCodes } ?: return@onReceive

            lobbyRegex.find(text)?.groupValues?.get(1)?.let { lobbyId = it }
        }

        on<LevelEvent.Load> {
            currentArea = if (mc.hasSingleplayerServer()) Island.SinglePlayer else pendingArea ?: Island.Unknown
            isInSkyblock = pendingSkyblock
            apiKnown = pendingKnown
            lobbyId = null
            pendingArea = null
            pendingSkyblock = false
            pendingKnown = false
        }
    }

    private fun islandFromMap(map: String): Island =
        if (map == "Dungeon") Island.Dungeon
        else Island.entries.firstOrNull { it.displayName.equals(map, true) } ?: Island.Unknown

    fun isCurrentArea(area: Island): Boolean =
        currentArea == Island.SinglePlayer || currentArea == area

    fun isCurrentArea(vararg areas: Island): Boolean =
        currentArea == Island.SinglePlayer || areas.any { currentArea == it }
}