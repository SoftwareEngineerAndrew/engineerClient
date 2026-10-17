package com.engineerclient.clearbreakdown

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** What one client tells its party during a clear. */
sealed interface Relayed {
    /** A secret it saw happen: [candidates] who could have got it, [exact] when that is the known finder. */
    data class Secret(val key: String, val roomKey: String, val roomName: String, val candidates: List<String>, val exact: Boolean) : Relayed

    /** Where it sees [player]: a room key, "" in no room, null when it no longer sees them. */
    data class Presence(val player: String, val roomKey: String?) : Relayed
}

/**
 * Clear Breakdown's frames on the ADR Online socket. Sent: {"t":"cb","d":[...]}; the site hands
 * them to the sender's party with "from" added. Items:
 *
 *     {"s":key,"r":roomKey,"n":roomName,"c":[names],"x":exact}   a secret
 *     {"p":name,"r":roomKey}                                     where someone is ("r" left out: not seen)
 *
 * The site closes a socket for frames over 4096 chars, so a batch is split under [frames]' limit.
 */
object RelayCodec {
    fun frames(items: List<Relayed>, maxChars: Int = 3500): List<String> {
        val out = ArrayList<String>()
        var batch = JsonArray()
        var size = 0
        for (item in items) {
            val json = encode(item)
            val len = json.toString().length + 1
            if (batch.size() > 0 && FRAME_OVERHEAD + size + len > maxChars) {
                out += frame(batch); batch = JsonArray(); size = 0
            }
            batch.add(json); size += len
        }
        if (batch.size() > 0) out += frame(batch)
        return out
    }

    fun decode(text: String): Pair<String, List<Relayed>>? = try {
        val o = JsonParser.parseString(text).asJsonObject
        if (o.str("t") != "cb") null
        else {
            val from = o.str("from")
            val d = o.get("d")
            if (from == null || d == null || !d.isJsonArray) null
            else from to d.asJsonArray.mapNotNull { item(it) }
        }
    } catch (_: Exception) {
        null
    }

    private const val FRAME_OVERHEAD = 16

    private fun frame(batch: JsonArray) = JsonObject().apply { addProperty("t", "cb"); add("d", batch) }.toString()

    private fun encode(item: Relayed): JsonObject = JsonObject().apply {
        when (item) {
            is Relayed.Secret -> {
                addProperty("s", item.key); addProperty("r", item.roomKey); addProperty("n", item.roomName)
                add("c", JsonArray().apply { item.candidates.forEach { add(it) } }); addProperty("x", item.exact)
            }
            is Relayed.Presence -> { addProperty("p", item.player); item.roomKey?.let { addProperty("r", it) } }
        }
    }

    private fun item(e: JsonElement): Relayed? {
        if (!e.isJsonObject) return null
        val o = e.asJsonObject
        o.str("p")?.let { return Relayed.Presence(it, o.str("r")) }
        val key = o.str("s") ?: return null
        val room = o.str("r") ?: return null
        val name = o.str("n") ?: return null
        val c = o.get("c")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val x = o.get("x")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: return null
        return Relayed.Secret(key, room, name, c.mapNotNull { n -> n.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }, x)
    }

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
