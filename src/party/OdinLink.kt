package com.engineerclient.party

import com.engineerclient.EngineerClient
import com.engineerclient.clearbreakdown.OdinSync
import com.engineerclient.pov.PoseShare
import com.engineerclient.tpmaze.TeamTpMaze
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.adm.features.impl.boss.MelodyMessage
import com.adm.features.impl.dungeon.map.DungeonMap
import com.adm.utils.network.WebSocketConnection
import com.adm.utils.skyblock.dungeon.DungeonUtils
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Websocket messages (melody progress, map sync, Team TP Maze) over ADM's party link first, the way Pose POV shares: straight
 * to teammates over TURN, `[14][channel][JSON]`. Odin's socket still carries them to anyone the link didn't reach, plain Odin
 * users included, and whatever comes either way goes to the same handlers, which take repeats fine.
 */
object OdinLink {
    private const val KIND = 14
    // TURN packets are 300 bytes; less its 20 of header and tag, and this frame's own 2.
    private const val MAX_JSON = 278

    /** A channel's socket, the field naming who sent a message, and other listeners on the same lobby channel. Index = wire channel. */
    private class Channel(val socket: WebSocketConnection, val nameField: String, val also: List<WebSocketConnection> = emptyList())

    private val channels: List<Channel> by lazy {
        listOf(
            Channel(MelodyMessage.melodyWebSocket, "username"),
            Channel(DungeonMap.syncSocket, "playerName", listOf(OdinSync.socket)),
            Channel(TeamTpMaze.socket, "from"),
        )
    }

    fun install() {
        channels.forEachIndexed { i, c -> c.socket.bridge = { send(i, it) } }
        PoseShare.onExtra(KIND) { sender, b -> EngineerClient.safely("odin link") { heard(sender, b) } }
    }

    /** Sends over the link; true only if every teammate surely got it, so the socket can be skipped. */
    private fun send(channel: Int, json: String): Boolean {
        val body = json.toByteArray(StandardCharsets.UTF_8)
        if (body.size > MAX_JSON) return false
        val reached = PoseShare.shareExtra(byteArrayOf(KIND.toByte(), channel.toByte()) + body)
        val teammates = DungeonUtils.dungeonTeammatesNoSelf.size
        return teammates > 0 && reached >= teammates
    }

    private fun heard(sender: String, b: ByteBuffer) {
        if (b.remaining() < 2) return
        val channel = channels.getOrNull(b.get().toInt() and 255) ?: return
        val json = StandardCharsets.UTF_8.decode(b).toString()
        // A teammate may only speak for themselves.
        val name = runCatching { field(json, channel.nameField) }.getOrNull() ?: return
        if (!name.equals(sender, ignoreCase = true)) return
        EngineerClient.mc.execute {
            channel.socket.receive(json)
            for (s in channel.also) s.receive(json)
        }
    }

    /** One top-level string field, read without building the tree the handlers parse again. */
    private fun field(json: String, name: String): String? {
        val r = JsonReader(StringReader(json))
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == name && r.peek() == JsonToken.STRING) return r.nextString()
            r.skipValue()
        }
        return null
    }
}
