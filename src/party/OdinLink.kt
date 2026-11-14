package com.engineerclient.party

import com.engineerclient.EngineerClient
import com.engineerclient.pov.PoseShare
import com.google.gson.JsonParser
import com.odtheking.odin.features.impl.boss.MelodyMessage
import com.odtheking.odin.features.impl.dungeon.map.DungeonMap
import com.odtheking.odin.utils.network.WebSocketConnection
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Odin's websocket messages (melody progress, map sync) over ADM's party link, the way Pose POV shares: straight to
 * teammates over TURN, `[14][channel][Odin's JSON]`. Odin's socket still carries them to anyone the link didn't reach,
 * plain Odin users included, and whatever comes either way goes to the same Odin handler, which takes repeats fine.
 */
object OdinLink {
    private const val KIND = 14
    // TURN packets are 300 bytes; less its 20 of header and tag, and this frame's own 2.
    private const val MAX_JSON = 278

    private val channels: List<Pair<WebSocketConnection, String>> by lazy {
        listOf(MelodyMessage.melodyWebSocket to "username", DungeonMap.syncSocket to "playerName")
    }

    fun install() {
        channels.forEachIndexed { i, (socket, _) -> socket.bridge = { send(i, it) } }
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
        val (socket, nameField) = channels.getOrNull(b.get().toInt() and 255) ?: return
        val json = StandardCharsets.UTF_8.decode(b).toString()
        // A teammate may only speak for themselves.
        val name = runCatching { JsonParser.parseString(json).asJsonObject[nameField].asString }.getOrNull() ?: return
        if (!name.equals(sender, ignoreCase = true)) return
        EngineerClient.mc.execute { socket.receive(json) }
    }
}
