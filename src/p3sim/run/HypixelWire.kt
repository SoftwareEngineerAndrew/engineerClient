package com.engineerclient.p3sim.run

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import kotlin.random.Random

/**
 * What the sim says to the mods that use Hypixel's mod API (Blade Addons, DTMap and Devonian read it; Odin does not): where the player is, and who
 * is in their party. On Hypixel the server sends these in the login tick; the sim sends them when a run starts, as SimNet messages that each
 * game turns into the API's own packets ([HypixelModApi]). Pure: the message forms, the server name and the answer delay. Tested.
 *
 * The values are measured over the Dungeon Recorder's real sessions: the server name is `mini` and a short run of digits and capitals; the party
 * answer follows the request by 0 to 3 server ticks.
 */
object HypixelWire {
    /** Server to game, at run start: the location (and the hello that makes the API believe it is connected). Data: [encodeLocation]. */
    const val LOCATION_KIND = "ads.engine.hypixel"

    /** Game to server: the game's mods asked for the party (`hypixel:party_info`). No data. */
    const val PARTY_REQUEST_KIND = "ads.engine.partyreq"

    /** Server to game: the answer. Data: [encodeParty]. */
    const val PARTY_KIND = "ads.engine.party"

    /** The server brand the client shows in F3 (the BungeeCord version of Hypixel's proxy and its game server software; as of 2026-10-15). */
    const val BRAND = "Hypixel BungeeCord (2026.10.9.1) <- Hygot 2026.10.15.1"

    /** One party member of the answer. */
    data class Member(val uuid: UUID, val leader: Boolean)

    /**
     * A dungeon server's name as Hypixel's location packet carries it: `mini`, one to three digits, then one or two capital letters
     * (`mini198BQ`, `mini7B`, `mini8H`).
     */
    fun serverName(random: Random): String {
        val digits = 1 + random.nextInt(3)
        val letters = 1 + random.nextInt(2)
        val sb = StringBuilder("mini")
        repeat(digits) { sb.append(('0' + random.nextInt(10))) }
        repeat(letters) { sb.append(('A' + random.nextInt(26))) }
        return sb.toString()
    }

    /**
     * Server ticks between a party request and its answer: 0 in 37 of 56 measured pairs, 1 in 10, 2 in 7 and 3 in 2.
     */
    fun partyDelayTicks(random: Random): Int {
        var n = random.nextInt(56)
        for ((delay, weight) in listOf(0 to 37, 1 to 10, 2 to 7, 3 to 2)) { if (n < weight) return delay; n -= weight }
        return 0
    }

    /** The party the answer lists: the first of [humans] is the leader (the host), everyone else a member. Humans first, then the bots. */
    fun partyMembers(humans: List<UUID>, bots: List<UUID>): List<Member> =
        (humans + bots).distinct().mapIndexed { i, id -> Member(id, leader = i == 0) }

    fun encodeLocation(serverName: String): String = JsonObject().also { it.addProperty("server", serverName) }.toString()

    /** The server name in a location message, or null when the message is not one. */
    fun decodeLocation(text: String): String? = try {
        JsonParser.parseString(text).asJsonObject.get("server")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    fun encodeParty(members: List<Member>): String {
        val arr = JsonArray()
        for (m in members) arr.add(JsonObject().also { it.addProperty("id", m.uuid.toString()); it.addProperty("leader", m.leader) })
        return JsonObject().also { it.add("members", arr) }.toString()
    }

    /** The members of an answer; empty when the message is not one (a bad id is skipped). */
    fun decodeParty(text: String): List<Member> = try {
        JsonParser.parseString(text).asJsonObject.getAsJsonArray("members").mapNotNull { e ->
            val o = e.asJsonObject
            val id = runCatching { UUID.fromString(o.get("id").asString) }.getOrNull() ?: return@mapNotNull null
            Member(id, o.get("leader")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false)
        }
    } catch (e: Exception) { emptyList() }
}
