package com.engineerclient.p3sim.clear.replay

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * A replay: one recorded run turned into what ADS needs to play it again (made outside the mod from a Dungeon Recorder recording, kept in
 * `<game dir>/config/engineerclient/ads/replays/<layout id>.adsreplay.json.gz`; the files hold real players' names and never ship).
 *
 * Every event is a JSON array `[k, kind, ...]`, k being the server tick counted from T0 (the tick of Mort's first line), in the order the
 * client met them. Kinds starting `o.` are what the recording's player sent (moves, item uses, clicks...); `mmv`, `mrot`, `mhead`, `manim`,
 * `mflags` and `eq` are the teammates' bodies; the rest is what the server did (chat, blocks, entities, Odin's rooms), for the differ.
 */
class ReplayFile(val id: String, val floor: String?, val self: Person, val party: List<Person>, val idToMate: Map<Int, String>, val events: List<JsonArray>, val mobs: List<Mob> = emptyList()) {
    class Person(val name: String, val uuid: String?, val cls: String?, val tex: String?, val sig: String?)

    /** One of Hypixel's mobs: its body, where it was first seen (k), its tag without HP ("✯ Healing Crypt Souleater") and the most HP the tag showed. */
    class Mob(val id: Int, val type: String?, val k: Int, val x: Double, val y: Double, val z: Double, val yaw: Float, val tag: String, val hp: Double)

    /** Events by k, in their order. */
    private val byK: Map<Int, List<JsonArray>> = events.groupBy { it[0].asInt }
    val firstK: Int = events.firstOrNull()?.get(0)?.asInt ?: 0
    val lastK: Int = events.lastOrNull()?.get(0)?.asInt ?: 0

    fun at(k: Int): List<JsonArray> = byK[k] ?: emptyList()

    /** Every event of [kind] (the differ's truth). */
    fun all(kind: String): List<JsonArray> = events.filter { it[1].asString == kind }

    /** The k the run reaches the boss (Odin's inBoss), or null. */
    val bossK: Int? by lazy { events.firstOrNull { it[0].asInt > 0 && it[1].asString == "dung" && it.size() > 3 && it[3].isJsonPrimitive && it[3].asBoolean }?.get(0)?.asInt }

    companion object {
        val dir: Path get() = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("config/engineerclient/ads/replays")

        fun list(): List<String> = try {
            Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".adsreplay.json.gz") }.map { it.removeSuffix(".adsreplay.json.gz") }.sorted().toList() }
        } catch (t: Throwable) { emptyList() }

        /** The replay whose id is [id] or starts with it. */
        fun resolve(id: String): String? { val all = list(); return all.firstOrNull { it == id } ?: all.filter { it.startsWith(id) }.singleOrNull() }

        fun load(id: String): ReplayFile {
            val file = dir.resolve("$id.adsreplay.json.gz")
            val o = GZIPInputStream(Files.newInputStream(file)).reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
            fun person(e: JsonElement?): Person? {
                val p = e?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
                fun s(k: String) = p.get(k)?.takeIf { it.isJsonPrimitive }?.asString
                return Person(s("name") ?: return null, s("uuid"), s("cls"), s("tex"), s("sig"))
            }
            val self = person(o.get("self")) ?: error("the replay names no player")
            val party = o.getAsJsonArray("party")?.mapNotNull { person(it) } ?: emptyList()
            val ids = HashMap<Int, String>()
            o.getAsJsonObject("idToMate")?.entrySet()?.forEach { (k, v) -> k.toIntOrNull()?.let { ids[it] = v.asString } }
            val ev = o.getAsJsonArray("ev").map { it.asJsonArray }
            val mobs = o.getAsJsonArray("mobs")?.mapNotNull { m ->
                val j = m.asJsonObject
                fun d(k: String) = j.get(k)?.takeIf { it.isJsonPrimitive }?.asDouble
                Mob(j.get("id").asInt, j.get("type")?.takeIf { it.isJsonPrimitive }?.asString, d("k")?.toInt() ?: 0, d("x") ?: return@mapNotNull null, d("y") ?: 0.0, d("z") ?: 0.0,
                    d("yaw")?.toFloat() ?: 0f, j.get("tag")?.asString?.trim() ?: return@mapNotNull null, d("hp") ?: 0.0)
            }.orEmpty()
            return ReplayFile(o.get("id").asString, o.get("floor")?.takeIf { it.isJsonPrimitive }?.asString, self, party, ids, ev, mobs)
        }
    }
}

internal fun JsonArray.d(i: Int): Double? = get(i).takeIf { it.isJsonPrimitive }?.asDouble
internal fun JsonArray.i(i: Int): Int? = get(i).takeIf { it.isJsonPrimitive }?.asInt
internal fun JsonArray.s(i: Int): String? = if (i < size()) get(i).takeIf { it.isJsonPrimitive }?.asString else null
internal fun JsonArray.o(i: Int): JsonObject? = if (i < size()) get(i).takeIf { it.isJsonObject }?.asJsonObject else null
