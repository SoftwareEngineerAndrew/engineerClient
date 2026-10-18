package com.engineerclient.p3sim.secrets

import com.engineerclient.p3sim.pack.Pos
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.RoomDesign
import com.engineerclient.p3sim.pack.rooms.RoomLever
import com.engineerclient.p3sim.pack.rooms.RoomSecret
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.PlacedRoom
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.RunBus
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.RunState

/** Invented rooms for the secrets tests: nothing here is recorded data. */
object SecretFixtures {
    fun secret(id: String, type: String, x: Int, y: Int, z: Int, facing: String? = null, lever: Pos? = null, alt: List<Pos> = emptyList(), baked: Boolean = false, src: String = "cleardata", bucket: String? = null) =
        RoomSecret(id, type, bucket, Pos(x, y, z), facing, lever, alt, baked, src)

    fun design(
        id: String, shape: String, secrets: List<RoomSecret>, crypts: List<List<Pos>> = emptyList(), walls: List<List<Pos>> = emptyList(),
        levers: List<RoomLever> = emptyList(), type: String = "normal",
    ): RoomDesign {
        val (w, d) = Placement.footprint(shape)
        return RoomDesign(
            id, id, id, null, type, shape, Placement.canonicalTiles(shape), w, d, 1, 69, 70, Rot.entries, null, null, null, emptyList(), emptyList(),
            secrets, levers, crypts, walls, emptyList(), emptyList(), emptyList(), null, null, emptyList(), emptyList(),
        )
    }

    fun room(index: Int, design: RoomDesign, tiles: List<Tile>, rot: Rot, max: Int, type: RoomType = RoomType.NORMAL): PlacedRoom {
        val placement = design.place(rot, tiles)
        return PlacedRoom(index, design.name, design.family, design.id, design, type, design.shape, tiles.sortedWith(compareBy({ it.z }, { it.x })), placement.rot, placement, max, null, null, null, null, null)
    }

    /** A bare chest site for the tests that only need a key to pick from. */
    fun chestSite(i: Int) = com.engineerclient.p3sim.clear.secrets.SecretSite(0, "c$i", com.engineerclient.p3sim.run.SecretKind.CHEST, i, 70, i, "north", null, false, 0, false)

    fun plan(vararg rooms: PlacedRoom): DungeonPlan =
        DungeonPlan("t", "F7", 1, rooms.toList(), emptyList(), rooms.map { it.index }, emptyList(), emptyList())

    class Harness(val plan: DungeonPlan) {
        val events = ArrayList<RunEvent>()
        var t = 100
        val bus = RunBus().also { b -> b.subscribe { events += it } }
        val state = RunState(plan, bus) { t }
        val secretPlan = com.engineerclient.p3sim.clear.secrets.SecretPlan.build(plan, kotlin.random.Random(1))
        var auto = true
        val book = com.engineerclient.p3sim.clear.secrets.SecretBook(secretPlan, state, bus, { t }, { auto })
    }

    /**
     * The standard test dungeon:
     *  room 0: a 1x1 at tile (1,1) rotated SOUTH, max 5: a chest, a locked chest with a lever, an item, a bat and an essence skull,
     *          one crypt tomb and one secret wall;
     *  room 1: a 1x2 at tiles (2,1),(3,1) SOUTH, max 3 with a single placeable chest (two to auto-credit);
     *  room 2: a 1x1 at tile (1,2) rotated WEST, max 2, a chest facing east at the canonical origin corner and an item;
     *  room 3: a 1x1 at tile (4,4), max 1, no placeable secret.
     */
    fun standard(): DungeonPlan {
        val d0 = design("R0", "1x1", listOf(
            secret("s1", "chest", 5, 70, 5, "east"),
            secret("L1", "lockedChest", 6, 70, 5, "north", lever = Pos(20, 71, 3), baked = true),
            secret("s2", "item", 10, 70, 10),
            secret("s3", "bat", 12, 72, 12),
            secret("s4", "essence", 15, 70, 15),
        ), crypts = listOf(listOf(Pos(25, 70, 25), Pos(26, 70, 25), Pos(25, 71, 25))), walls = listOf(listOf(Pos(1, 70, 10), Pos(1, 70, 11), Pos(1, 71, 10), Pos(1, 71, 11), Pos(1, 72, 10), Pos(1, 72, 11))),
            levers = listOf(RoomLever(Pos(20, 71, 3), listOf(Pos(2, 70, 20), Pos(2, 71, 20)))))
        val d1 = design("R1", "1x2", listOf(secret("s1", "chest", 40, 70, 8, "south")))
        val d2 = design("R2", "1x1", listOf(secret("s1", "chest", 0, 70, 0, "east"), secret("s2", "item", 3, 70, 7)))
        val d3 = design("R3", "1x1", emptyList())
        return plan(
            room(0, d0, listOf(Tile(1, 1)), Rot.SOUTH, 5),
            room(1, d1, listOf(Tile(2, 1), Tile(3, 1)), Rot.SOUTH, 3),
            room(2, d2, listOf(Tile(1, 2)), Rot.WEST, 2),
            room(3, d3, listOf(Tile(4, 4)), Rot.SOUTH, 1),
        )
    }
}
