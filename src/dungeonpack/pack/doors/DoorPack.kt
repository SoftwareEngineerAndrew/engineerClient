package com.engineerclient.dungeonpack.pack.doors

import com.engineerclient.dungeonpack.pack.arr
import com.engineerclient.dungeonpack.pack.asArr
import com.engineerclient.dungeonpack.pack.asIntOrNull
import com.engineerclient.dungeonpack.pack.asObj
import com.engineerclient.dungeonpack.pack.asStr
import com.engineerclient.dungeonpack.pack.obj
import com.engineerclient.dungeonpack.pack.readGzipJson
import com.engineerclient.dungeonpack.pack.str
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.InputStream

/** One cell of a door unit in the S-axis frame: [dd] across the gap column (0 the gap, negative the north/west room), [a] along the side, [y] absolute. */
class UnitCell(val dd: Int, val a: Int, val y: Int, val state: String)

/** One of the six ordinary door looks: its 245 [cells], and the [keyCells] a wither or fairy door writes over them (the heads). */
class DoorUnit(val name: String, val cells: List<UnitCell>, val keyCells: List<UnitCell>)

/** A cell of a room side's patch in the design's canonical frame. */
class PatchCell(val x: Int, val y: Int, val z: Int, val state: String)

/**
 * How one room side differs from its base volume. [open]: as built with a door (the door unit and its floor go over it); [closed]: as
 * built without one; [closedEstimated]: a guess for a side never recorded without a door whose base has a door baked in. Null = not known.
 */
class SidePatches(val open: List<PatchCell>?, val closed: List<PatchCell>?, val closedEstimated: List<PatchCell>?)

/**
 * The door pack (`ads/packs/doors.json.gz`, format `ads-room-doors`): Hypixel's door units and every room side's open and closed look,
 * measured from the chunks of 140 recorded F7 runs (ads-handoffs results/room-doors.md). Pure; states carry the `minecraft:` prefix.
 */
class DoorPack(
    /** The six ordinary styles, each picked by 1 in 6 of normal, wither and fairy doors. */
    val styles: List<DoorUnit>,
    /** The entrance door's unit (COBBLE_SB in its normal version). */
    val entrance: DoorUnit,
    val blood: DoorUnit,
    /** Head texture hashes: on a wither or fairy door, on the blood door, and on an SB_ACACIA normal door. */
    val keyHead: String, val bloodHead: String, val plainHead: String,
    /** By design id, then `"tx,tz,side"` (canonical tile and side). */
    private val rooms: Map<String, Map<String, SidePatches>>,
    /**
     * Blocks a design has outside its own box, in the gap column beside it (canonical cells, z -1 for its north side): Entrance__b3 walls
     * up the gap by its entrance door (DR 30 of 30 runs; the door unit goes over it). By design id.
     */
    val gapWalls: Map<String, List<PatchCell>> = emptyMap(),
) {
    fun side(design: String, tx: Int, tz: Int, side: Char): SidePatches? = rooms[design]?.get("$tx,$tz,$side")

    companion object {
        private const val PATH = "/assets/engineerclient/dungeonpack/packs/doors.json.gz"

        /** The pack bundled in the jar, read once. */
        val bundled: DoorPack by lazy { DoorPack::class.java.getResourceAsStream(PATH)!!.use { parse(it) } }

        fun parse(stream: InputStream): DoorPack = parse(readGzipJson(stream))

        fun parse(j: JsonObject): DoorPack {
            val f = j.obj("frames") ?: error("no frames")
            val st = f.obj("styles") ?: error("no styles")
            val heads = f.obj("heads")
            val rooms = j.obj("rooms")?.entrySet()?.associate { (id, sides) ->
                id to sides.asObj()!!.entrySet().associate { (k, v) ->
                    val o = v.asObj()!!
                    k to SidePatches(patch(o, "open"), patch(o, "closed"), patch(o, "closedEstimated"))
                }
            }.orEmpty()
            return DoorPack(
                styles = st.entrySet().map { (name, s) -> unit(name, s.asObj()!!) },
                entrance = unit("ENTRANCE", f.obj("entrance") ?: error("no entrance")),
                blood = unit("BLOOD", f.obj("blood") ?: error("no blood")),
                keyHead = heads?.str("key").orEmpty(), bloodHead = heads?.str("blood").orEmpty(), plainHead = heads?.str("SB_ACACIA_normal").orEmpty(),
                rooms = rooms,
                gapWalls = j.obj("gapWalls")?.entrySet()?.associate { (id, v) -> id to (patch(v.asObj()!!.let { o -> JsonObject().also { it.add("x", o) } }, "x") ?: emptyList()) }.orEmpty(),
            )
        }

        private fun unit(name: String, o: JsonObject) = DoorUnit(name, unitCells(o.arr("cells")), unitCells(o.arr("keyCells")))

        private fun unitCells(a: JsonArray?): List<UnitCell> = a?.mapNotNull { e ->
            val c = e.asArr() ?: return@mapNotNull null
            UnitCell(c[0].asIntOrNull()!!, c[1].asIntOrNull()!!, c[2].asIntOrNull()!!, state(c[3].asStr()!!))
        }.orEmpty()

        private fun patch(o: JsonObject, key: String): List<PatchCell>? = o.obj(key)?.arr("cells")?.mapNotNull { e ->
            val c = e.asArr() ?: return@mapNotNull null
            PatchCell(c[0].asIntOrNull()!!, c[1].asIntOrNull()!!, c[2].asIntOrNull()!!, state(c[3].asStr()!!))
        }

        private fun state(s: String) = if (':' in s.substringBefore('[')) s else "minecraft:$s"
    }
}
