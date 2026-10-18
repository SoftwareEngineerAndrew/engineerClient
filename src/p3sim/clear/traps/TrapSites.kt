package com.engineerclient.p3sim.clear.traps

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * A floor that falls: which room design, what sets it off and which of the design's `fallingBlocks` go. `design` is the rooms pack's design id
 * (`New_Trap`); `trigger` is `stand` (a player stands over the floor) or `string` (a player touches the string whose hook is [hook]);
 * [cluster] keeps only the connected part of the design's falling cells that holds this cell (the Old Trap's list holds a second, separate
 * floor that was never seen to fall); [also] are boxes `[x0, y0, z0, x1, y1, z1]` of further cells that go with it (the string and the block under it).
 */
class FallRule(val design: String, val trigger: String, val hook: IntArray?, val cluster: IntArray?, val also: List<IntArray>)

/** A dispenser as `traps.json` lists it: its cell (canonical frame, absolute y) and the way it faces (`north south east west up down`). */
class Emitter(val x: Int, val y: Int, val z: Int, val facing: String) {
    /** The facing as a step on each canonical axis (north is -z, east is +x). */
    val dx: Int get() = when (facing) { "east" -> 1; "west" -> -1; else -> 0 }
    val dy: Int get() = when (facing) { "up" -> 1; "down" -> -1; else -> 0 }
    val dz: Int get() = when (facing) { "south" -> 1; "north" -> -1; else -> 0 }
}

/**
 * A bank of dispensers that fire an arrow each, all in one tick, every [period] ticks ([perDispenser] arrows from each). Mined from real runs: the
 * banks fire on a fixed beat whether or not anyone is in the room.
 */
class ArrowRule(val design: String, val period: Int, val perDispenser: Int, val dispensers: List<Emitter>)

/**
 * The flamethrowers of one part of a room: the dispensers that fire them. On every beat ([FlameStream.period]) [perBeat] of them fire (one in
 * most rooms, one to three in the New Trap's pit). With a [region] box `[x0, y0, z0, x1, y1, z1]` only a player inside it sets them off; [pickRange]
 * narrows which emitters can fire to those that close to a player (default [FlameStream.range]).
 */
class FlameRule(val design: String, val emitters: List<Emitter>, val region: IntArray?, val perBeatMin: Int, val perBeatMax: Int, val pickRange: Double?)

/**
 * How a flame behaves, the same everywhere: it fires every [period] ticks, a stream of [reach] blocks that is [halfWidth] to each side and
 * [height] tall, live for [liveTicks] ticks after the shot; a player within [range] blocks of an emitter wakes it.
 */
class FlameStream(
    val period: Int = 8, val reach: Double = 6.0, val halfWidth: Double = 1.0, val height: Double = 1.4, val liveTicks: Int = 5, val range: Double = 16.0,
)

/** How a dispenser arrow flies: [speed] and [inaccuracy] as vanilla's dispenser; a bank fires only while a player is within [range] blocks; an arrow goes after [maxTicks]. */
class ArrowFlight(val speed: Float = 1.5f, val inaccuracy: Float = 6.0f, val range: Double = 48.0, val maxTicks: Int = 60)

/**
 * The per-room hazard data of `traps.json` beyond the crusher paces: falling floors, arrow banks and flamethrowers. Coordinates are in the room's
 * canonical frame, y absolute. Without the file there are none of these hazards.
 */
class TrapSites(
    val falls: List<FallRule>,
    val arrows: List<ArrowRule> = emptyList(),
    val flames: List<FlameRule> = emptyList(),
    val flameStream: FlameStream = FlameStream(),
    val arrowFlight: ArrowFlight = ArrowFlight(),
) {
    companion object {
        val NONE = TrapSites(emptyList())

        private fun ints(a: JsonArray?): IntArray? = a?.map { it.asInt }?.toIntArray()

        private fun emitters(a: JsonArray?): List<Emitter> = a?.mapNotNull { e ->
            val t = e.takeIf { it.isJsonArray }?.asJsonArray?.takeIf { it.size() == 4 } ?: return@mapNotNull null
            val f = t[3].asString
            if (f !in FACINGS) null else Emitter(t[0].asInt, t[1].asInt, t[2].asInt, f)
        }.orEmpty()

        private val FACINGS = setOf("north", "south", "east", "west", "up", "down")

        fun parse(root: JsonObject): TrapSites {
            val falls = root.getAsJsonArray("falls")?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val design = o.get("design")?.asString ?: return@mapNotNull null
                val trigger = o.get("trigger")?.asString?.takeIf { it == "stand" || it == "string" } ?: return@mapNotNull null
                val hook = ints(o.getAsJsonArray("hook"))?.takeIf { it.size == 3 }
                if (trigger == "string" && hook == null) return@mapNotNull null
                val also = o.getAsJsonArray("also")?.mapNotNull { b -> ints(b.takeIf { it.isJsonArray }?.asJsonArray)?.takeIf { it.size == 6 } }.orEmpty()
                FallRule(design, trigger, hook, ints(o.getAsJsonArray("cluster"))?.takeIf { it.size == 3 }, also)
            }.orEmpty()
            val arrows = root.getAsJsonArray("arrows")?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val design = o.get("design")?.asString ?: return@mapNotNull null
                val period = o.get("period")?.asInt?.takeIf { it >= 1 } ?: return@mapNotNull null
                val ds = emitters(o.getAsJsonArray("dispensers"))
                if (ds.isEmpty()) null else ArrowRule(design, period, (o.get("perDispenser")?.asInt ?: 1).coerceIn(1, 8), ds)
            }.orEmpty()
            val flames = root.getAsJsonArray("flames")?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val design = o.get("design")?.asString ?: return@mapNotNull null
                val ems = emitters(o.getAsJsonArray("emitters"))
                if (ems.isEmpty()) return@mapNotNull null
                val pb = ints(o.getAsJsonArray("perBeat"))?.takeIf { it.size == 2 && it[0] >= 1 && it[1] >= it[0] } ?: intArrayOf(1, 1)
                FlameRule(design, ems, ints(o.getAsJsonArray("region"))?.takeIf { it.size == 6 }, pb[0], pb[1], o.get("pickRange")?.asDouble)
            }.orEmpty()
            val fs = root.getAsJsonObject("flameStream")
            val stream = if (fs == null) FlameStream() else FlameStream(
                fs.get("period")?.asInt?.takeIf { it >= 1 } ?: 8, fs.get("reach")?.asDouble ?: 6.0, fs.get("halfWidth")?.asDouble ?: 1.0,
                fs.get("height")?.asDouble ?: 1.4, fs.get("liveTicks")?.asInt?.takeIf { it >= 1 } ?: 5, fs.get("range")?.asDouble ?: 16.0)
            val af = root.getAsJsonObject("arrowFlight")
            val flight = if (af == null) ArrowFlight() else ArrowFlight(
                af.get("speed")?.asFloat ?: 1.5f, af.get("inaccuracy")?.asFloat ?: 6.0f, af.get("range")?.asDouble ?: 48.0, af.get("maxTicks")?.asInt?.takeIf { it >= 1 } ?: 60)
            return TrapSites(falls, arrows, flames, stream, flight)
        }
    }
}
