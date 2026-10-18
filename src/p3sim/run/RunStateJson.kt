package com.engineerclient.p3sim.run

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.UUID

/**
 * The savepoint form of [RunState]: [write] turns every field of the hub into JSON, [read] puts it back into the same objects.
 * Pure (no Minecraft), so a round trip is unit-tested. Fields a module keeps in `extras` are NOT here: the module that owns them
 * saves them in its own `RunSystem.snapshot` (ARCHITECTURE 7).
 *
 * Reading is forgiving: a missing field keeps its current value, an unknown field is ignored, and a room, door or puzzle index
 * outside the plan is skipped, so a snapshot from an older build of the engine still restores what it can.
 */
object RunStateJson {
    fun write(s: RunState): JsonObject = JsonObject().also { o ->
        o.add("rooms", JsonArray().also { a ->
            for (r in s.rooms) a.add(JsonObject().also { j ->
                j.addProperty("check", r.check.name)
                j.nullable("tDiscovered", r.tDiscovered); j.nullable("tWhite", r.tWhite); j.nullable("tGreen", r.tGreen); j.nullable("tRed", r.tRed)
                j.addProperty("secretsFound", r.secretsFound)
                j.addProperty("mobsRequired", r.mobsRequired); j.addProperty("mobsRequiredAlive", r.mobsRequiredAlive)
                j.add("occupants", uuids(r.occupants)); j.add("visitors", uuids(r.visitors))
            })
        })
        o.add("doors", JsonArray().also { a ->
            for (d in s.doors) a.add(JsonObject().also { j ->
                j.addProperty("phase", d.phase.name); j.nullable("openedAt", d.openedAt); d.openedBy?.let { j.addProperty("openedBy", it.toString()) }
            })
        })
        o.add("keys", JsonObject().also { j ->
            val k = s.keys
            j.addProperty("witherHeld", k.witherHeld); j.addProperty("bloodHeld", k.bloodHeld)
            j.addProperty("witherObtained", k.witherObtained); j.addProperty("witherUsed", k.witherUsed)
            j.addProperty("bloodObtained", k.bloodObtained); j.addProperty("bloodUsed", k.bloodUsed)
        })
        o.add("counters", JsonObject().also { j -> j.addProperty("opened", s.counters.opened); j.addProperty("completed", s.counters.completed) })
        o.add("secrets", JsonObject().also { j ->
            val c = s.secrets
            j.addProperty("teamFound", c.teamFound); j.addProperty("teamMax", c.teamMax); j.addProperty("crypts", c.crypts)
            j.addProperty("mimicKilled", c.mimicKilled); j.addProperty("princeKilled", c.princeKilled); j.addProperty("batKilled", c.batKilled)
            j.addProperty("witherEssence", c.witherEssence)
            j.add("byPlayer", JsonObject().also { m -> for ((u, n) in c.byPlayer) m.addProperty(u.toString(), n) })
            j.add("blessings", JsonObject().also { m -> for ((k, n) in c.blessings) m.addProperty(k, n) })
        })
        o.add("deaths", JsonObject().also { j ->
            j.addProperty("teamDeaths", s.deaths.teamDeaths)
            j.add("log", JsonArray().also { a -> for ((t, u) in s.deaths.log) a.add(JsonObject().also { e -> e.addProperty("t", t); e.addProperty("player", u.toString()) }) })
        })
        o.add("score", JsonObject().also { j ->
            val c = s.score
            j.addProperty("score", c.score); j.addProperty("clearedPct", c.clearedPct); j.addProperty("lastUpdateT", c.lastUpdateT)
            j.add("history", JsonArray().also { a -> for ((t, v) in c.history) a.add(JsonArray().also { e -> e.add(t); e.add(v) }) })
        })
        o.add("puzzles", JsonArray().also { a ->
            for (p in s.puzzles) a.add(JsonObject().also { j ->
                j.addProperty("room", p.roomIndex); j.addProperty("kind", p.kind); j.addProperty("tabName", p.tabName)
                j.addProperty("status", p.status.name); j.addProperty("revealed", p.revealed); j.addProperty("failures", p.failures)
                p.solvedBy?.let { j.addProperty("solvedBy", it.toString()) }
            })
        })
        o.add("players", JsonArray().also { a ->
            for (p in s.players.values) a.add(JsonObject().also { j ->
                j.addProperty("uuid", p.uuid.toString()); j.addProperty("name", p.name); p.dungeonClass?.let { j.addProperty("class", it) }
                j.addProperty("bot", p.isBot)
                j.addProperty("hp", p.hpPercent); j.addProperty("ghost", p.ghost); j.addProperty("deaths", p.deaths); j.addProperty("secrets", p.secretsFound)
                p.roomIndex?.let { j.addProperty("room", it) }
                j.addProperty("x", p.x); j.addProperty("y", p.y); j.addProperty("z", p.z)
                j.addProperty("lastHitT", p.lastHitT); j.nullable("reviveAtT", p.reviveAtT)
            })
        })
        o.add("milestones", JsonObject().also { j ->
            val m = s.milestones
            j.nullable("bloodKeyT", m.bloodKeyT); j.nullable("bloodOpenT", m.bloodOpenT); j.nullable("watcherDoneT", m.watcherDoneT)
            j.nullable("bossEntryT", m.bossEntryT); j.nullable("bossDefeatedT", m.bossDefeatedT); j.nullable("endT", m.endT)
        })
    }

    fun read(s: RunState, o: JsonObject) {
        o.arr("rooms")?.forEachIndexed { i, e ->
            val r = s.rooms.getOrNull(i) ?: return@forEachIndexed
            val j = e.asObjOrNull() ?: return@forEachIndexed
            j.str("check")?.let { n -> CheckMark.entries.firstOrNull { it.name == n }?.let { r.check = it } }
            r.tDiscovered = j.intOrNull("tDiscovered"); r.tWhite = j.intOrNull("tWhite"); r.tGreen = j.intOrNull("tGreen"); r.tRed = j.intOrNull("tRed")
            j.int("secretsFound")?.let { r.secretsFound = it }
            j.int("mobsRequired")?.let { r.mobsRequired = it }; j.int("mobsRequiredAlive")?.let { r.mobsRequiredAlive = it }
            r.occupants.clear(); r.occupants += readUuids(j.arr("occupants"))
            r.visitors.clear(); r.visitors += readUuids(j.arr("visitors"))
        }
        o.arr("doors")?.forEachIndexed { i, e ->
            val d = s.doors.getOrNull(i) ?: return@forEachIndexed
            val j = e.asObjOrNull() ?: return@forEachIndexed
            j.str("phase")?.let { n -> DoorPhase.entries.firstOrNull { it.name == n }?.let { d.phase = it } }
            d.openedAt = j.intOrNull("openedAt"); d.openedBy = j.str("openedBy")?.let(::uuidOrNull)
        }
        o.obj("keys")?.let { j ->
            val k = s.keys
            j.int("witherHeld")?.let { k.witherHeld = it }; j.bool("bloodHeld")?.let { k.bloodHeld = it }
            j.int("witherObtained")?.let { k.witherObtained = it }; j.int("witherUsed")?.let { k.witherUsed = it }
            j.bool("bloodObtained")?.let { k.bloodObtained = it }; j.bool("bloodUsed")?.let { k.bloodUsed = it }
        }
        o.obj("counters")?.let { j -> j.int("opened")?.let { s.counters.opened = it }; j.int("completed")?.let { s.counters.completed = it } }
        o.obj("secrets")?.let { j ->
            val c = s.secrets
            j.int("teamFound")?.let { c.teamFound = it }; j.int("teamMax")?.let { c.teamMax = it }; j.int("crypts")?.let { c.crypts = it }
            j.bool("mimicKilled")?.let { c.mimicKilled = it }; j.bool("princeKilled")?.let { c.princeKilled = it }; j.bool("batKilled")?.let { c.batKilled = it }
            j.int("witherEssence")?.let { c.witherEssence = it }
            c.byPlayer.clear()
            j.obj("byPlayer")?.entrySet()?.forEach { (k, v) -> uuidOrNull(k)?.let { u -> v.asIntOrNull()?.let { n -> c.byPlayer[u] = n } } }
            c.blessings.clear()
            j.obj("blessings")?.entrySet()?.forEach { (k, v) -> v.asIntOrNull()?.let { n -> c.blessings[k] = n } }
        }
        o.obj("deaths")?.let { j ->
            j.int("teamDeaths")?.let { s.deaths.teamDeaths = it }
            s.deaths.log.clear()
            j.arr("log")?.forEach { e ->
                val x = e.asObjOrNull() ?: return@forEach
                val t = x.int("t") ?: return@forEach
                val u = x.str("player")?.let(::uuidOrNull) ?: return@forEach
                s.deaths.log += t to u
            }
        }
        o.obj("score")?.let { j ->
            val c = s.score
            j.int("score")?.let { c.score = it }; j.int("clearedPct")?.let { c.clearedPct = it }; j.int("lastUpdateT")?.let { c.lastUpdateT = it }
            c.history.clear()
            j.arr("history")?.forEach { e -> val a = e.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach; if (a.size() >= 2) c.history += a[0].asInt to a[1].asInt }
        }
        o.arr("puzzles")?.let { a -> readPuzzles(s, a) }
        o.arr("players")?.let { a -> readPlayers(s, a) }
        o.obj("milestones")?.let { j ->
            val m = s.milestones
            m.bloodKeyT = j.intOrNull("bloodKeyT"); m.bloodOpenT = j.intOrNull("bloodOpenT"); m.watcherDoneT = j.intOrNull("watcherDoneT")
            m.bossEntryT = j.intOrNull("bossEntryT"); m.bossDefeatedT = j.intOrNull("bossDefeatedT"); m.endT = j.intOrNull("endT")
        }
    }

    /** The puzzle records are `val` in their identity, so a record whose room and kind match is updated in place and any other is rebuilt. */
    private fun readPuzzles(s: RunState, a: JsonArray) {
        val out = ArrayList<PuzzleRecord>()
        for ((i, e) in a.withIndex()) {
            val j = e.asObjOrNull() ?: continue
            val room = j.int("room") ?: continue; val kind = j.str("kind") ?: continue; val tab = j.str("tabName") ?: kind
            val rec = s.puzzles.getOrNull(i)?.takeIf { it.roomIndex == room && it.kind == kind && it.tabName == tab } ?: PuzzleRecord(room, kind, tab)
            j.str("status")?.let { n -> PuzzleStatus.entries.firstOrNull { it.name == n }?.let { rec.status = it } }
            j.bool("revealed")?.let { rec.revealed = it }; j.int("failures")?.let { rec.failures = it }
            rec.solvedBy = j.str("solvedBy")?.let(::uuidOrNull)
            out += rec
        }
        s.puzzles.clear(); s.puzzles += out
    }

    private fun readPlayers(s: RunState, a: JsonArray) {
        val keep = LinkedHashMap<UUID, PlayerRecord>()
        for (e in a) {
            val j = e.asObjOrNull() ?: continue
            val u = j.str("uuid")?.let(::uuidOrNull) ?: continue
            val rec = s.players[u] ?: PlayerRecord(u, j.str("name") ?: "?", j.str("class"), j.bool("bot") ?: false)
            j.dbl("hp")?.let { rec.hpPercent = it }; j.bool("ghost")?.let { rec.ghost = it }
            j.int("deaths")?.let { rec.deaths = it }; j.int("secrets")?.let { rec.secretsFound = it }
            rec.roomIndex = j.intOrNull("room")
            j.dbl("x")?.let { rec.x = it }; j.dbl("y")?.let { rec.y = it }; j.dbl("z")?.let { rec.z = it }
            j.int("lastHitT")?.let { rec.lastHitT = it }
            rec.reviveAtT = j.intOrNull("reviveAtT")
            keep[u] = rec
        }
        s.players.clear(); s.players.putAll(keep)
    }

    // ---- small JSON helpers (this file only) ---------------------------------------------------------------------------------------

    private fun JsonObject.nullable(k: String, v: Int?) { if (v != null) addProperty(k, v) }
    private fun uuids(c: Collection<UUID>) = JsonArray().also { a -> c.forEach { a.add(it.toString()) } }
    private fun readUuids(a: JsonArray?): List<UUID> = a?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.let(::uuidOrNull) }.orEmpty()
    private fun uuidOrNull(s: String): UUID? = try { UUID.fromString(s) } catch (e: IllegalArgumentException) { null }

    private fun JsonElement.asObjOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonElement.asIntOrNull(): Int? = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    private fun JsonObject.arr(k: String): JsonArray? = get(k)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(k: String): Int? = get(k)?.asIntOrNull()
    private fun JsonObject.intOrNull(k: String): Int? = get(k)?.asIntOrNull()
    private fun JsonObject.dbl(k: String): Double? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    private fun JsonObject.bool(k: String): Boolean? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
