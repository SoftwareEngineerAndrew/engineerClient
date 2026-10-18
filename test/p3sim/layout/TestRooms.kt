package com.engineerclient.p3sim.layout

import com.engineerclient.p3sim.clear.layout.RoomInstance
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.rooms.DoorSlot
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.RoomBlockEntity
import com.engineerclient.p3sim.pack.rooms.RoomDesign
import com.engineerclient.p3sim.pack.rooms.RoomVolume

/** Synthetic 1x1 rooms for the layout tests: a stone floor, two-block walls with 3 x 4 openings where a side is open. Nothing here is recorded data. */
object TestRooms {
    const val WALL = "minecraft:stone_bricks"
    const val FLOOR = "minecraft:stone"
    private const val Y0 = 66
    private const val H = 14

    /**
     * A 31 x 31 room. [open] sides have their opening air in the two edge planes; [closedWith] fills a closed side's opening in plane 0 with
     * that block (a door of a recorded run); [thickWalls] makes the walls [thickWalls] planes deep (default 2).
     */
    fun volume(open: Set<Char> = emptySet(), closedWith: Map<Char, String> = emptyMap(), thickWalls: Int = 2, wall: String = WALL): RoomVolume {
        val palette = listOf("minecraft:air", FLOOR, wall) + closedWith.values.distinct()
        val cells = ShortArray(31 * 31 * H)
        fun idx(x: Int, y: Int, z: Int) = ((y - Y0) * 31 + z) * 31 + x
        for (y in Y0 until Y0 + H) for (z in 0 until 31) for (x in 0 until 31) {
            val v = when {
                y <= 68 -> 1
                y in 69..73 && (x < thickWalls || z < thickWalls || x > 30 - thickWalls || z > 30 - thickWalls) -> 2
                y >= 77 -> 1
                else -> 0
            }
            cells[idx(x, y, z)] = v.toShort()
        }
        fun windowCells(side: Char, depths: IntRange) = buildList {
            for (a in 14..16) for (dep in depths) for (y in 69..72) add(when (side) {
                'N' -> Triple(a, y, dep); 'S' -> Triple(a, y, 30 - dep); 'W' -> Triple(dep, y, a); else -> Triple(30 - dep, y, a)
            })
        }
        for (side in open) for ((x, y, z) in windowCells(side, 0 until thickWalls)) cells[idx(x, y, z)] = 0
        for ((side, block) in closedWith) for ((x, y, z) in windowCells(side, 0..0)) cells[idx(x, y, z)] = palette.indexOf(block).toShort()
        return RoomVolume(31, 31, H, Y0, palette, cells)
    }

    fun design(id: String, vol: RoomVolume, open: Set<Char>, entities: List<RoomBlockEntity> = emptyList()): RoomDesign = RoomDesign(
        id = id, name = id.replace('_', ' '), family = id, buildKey = null, type = "normal", shape = "1x1", tiles = listOf(Tile(0, 0)),
        w = 31, d = 31, h = H, y0 = Y0, roofY = 79, rotations = Rot.entries, doors = null, source = null, vol = null,
        slots = "NESW".map { DoorSlot(Tile(0, 0), it, it in open, null) }, blockEntities = entities, secrets = emptyList(), levers = emptyList(),
        crypts = emptyList(), superboomWalls = emptyList(), fallingBlocks = emptyList(), crushers = emptyList(), tripwires = emptyList(),
        puzzle = null, odin = null, variants = emptyList(), gaps = emptyList(),
    )

    fun withSlots(d: RoomDesign, slots: List<DoorSlot>): RoomDesign = RoomDesign(
        id = d.id, name = d.name, family = d.family, buildKey = d.buildKey, type = d.type, shape = d.shape, tiles = d.tiles,
        w = d.w, d = d.d, h = d.h, y0 = d.y0, roofY = d.roofY, rotations = d.rotations, doors = d.doors, source = d.source, vol = d.vol,
        slots = slots, blockEntities = d.blockEntities, secrets = d.secrets, levers = d.levers, crypts = d.crypts, superboomWalls = d.superboomWalls,
        fallingBlocks = d.fallingBlocks, crushers = d.crushers, tripwires = d.tripwires, puzzle = d.puzzle, odin = d.odin, variants = d.variants, gaps = d.gaps,
    )

    fun withType(d: RoomDesign, type: String, roofY: Int): RoomDesign = RoomDesign(
        id = d.id, name = d.name, family = d.family, buildKey = d.buildKey, type = type, shape = d.shape, tiles = d.tiles,
        w = d.w, d = d.d, h = d.h, y0 = d.y0, roofY = roofY, rotations = d.rotations, doors = d.doors, source = d.source, vol = d.vol,
        slots = d.slots, blockEntities = d.blockEntities, secrets = d.secrets, levers = d.levers, crypts = d.crypts, superboomWalls = d.superboomWalls,
        fallingBlocks = d.fallingBlocks, crushers = d.crushers, tripwires = d.tripwires, puzzle = d.puzzle, odin = d.odin, variants = d.variants, gaps = d.gaps,
    )

    fun instance(id: String, tx: Int, tz: Int, rot: Rot = Rot.SOUTH, open: Set<Char> = emptySet(), closedWith: Map<Char, String> = emptyMap(), thickWalls: Int = 2): RoomInstance {
        val vol = volume(open, closedWith, thickWalls)
        val d = design(id, vol, open)
        return RoomInstance(d, vol, d.place(rot, listOf(Tile(tx, tz))))
    }

    /** A virtual world: the rooms' blocks (rotation applied to the position only, the test states are full cubes) with the edits on top. */
    fun world(rooms: List<RoomInstance>, edits: List<com.engineerclient.p3sim.clear.layout.Edit>): Map<Triple<Int, Int, Int>, String> {
        val m = HashMap<Triple<Int, Int, Int>, String>()
        for (r in rooms) {
            val v = r.volume
            for (y in v.y0 until v.y0 + v.h) for (z in 0 until v.d) for (x in 0 until v.w) {
                val s = v.stateAt(x, y, z)
                if (s == RoomVolume.AIR) continue
                val w = r.placement.toWorld(x, y, z)
                m[Triple(w.x, w.y, w.z)] = s
            }
        }
        for (e in edits) { val k = Triple(e.x, e.y, e.z); if (e.state == "minecraft:air") m.remove(k) else m[k] = e.state }
        return m
    }
}
