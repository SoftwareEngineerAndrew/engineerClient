package com.engineerclient.clearbreakdown

import com.google.gson.Gson
import com.odtheking.odin.features.impl.render.ClickGUIModule
import com.odtheking.odin.utils.network.webSocket
import com.odtheking.odin.utils.skyblock.LocationUtils

/**
 * Odin's dungeon map sync, read by Clear Breakdown on a connection of its own: every Odin user in
 * the lobby says which room they're in and how many of its secrets their action bar shows. Odin's
 * own socket (DungeonMap.syncSocket) takes a single message handler, and setting it would break
 * Odin's map, so this is a second listener on the same lobby. It never sends.
 */
object OdinSync {
    /** A teammate's report: who, the room's tiles (Odin's packed IVec2s) and its found secrets. */
    @Volatile var onPayload: (player: String, tiles: List<Long>, found: Int) -> Unit = { _, _, _ -> }

    private val gson = Gson()

    // Odin's SyncPayload; gson writes each IVec2 (a value class) as {"data": <packed long>}.
    private class Msg(val playerName: String?, val roomName: String?, val tiles: List<Tile>?, val foundSecrets: Int?)
    private class Tile(val data: Long?)

    private val socket = webSocket {
        onMessage { text ->
            val m = try { gson.fromJson(text, Msg::class.java) } catch (_: Exception) { null } ?: return@onMessage
            val player = m.playerName ?: return@onMessage
            val tiles = m.tiles?.map { it.data ?: return@onMessage } ?: return@onMessage
            val found = m.foundSecrets ?: return@onMessage
            if (m.roomName == null) return@onMessage
            onPayload(player, tiles, found)
        }
    }

    /** At a floor's start: Odin's socket server, this lobby's channel. */
    fun connect() {
        LocationUtils.lobbyId?.let { socket.connect("${ClickGUIModule.webSocketUrl}$it") }
    }

    fun shutdown() = socket.shutdown()
}
