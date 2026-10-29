package com.engineerclient.dungeonpack.rooms

import com.engineerclient.dungeonpack.pack.layouts.DoorKind
import com.engineerclient.dungeonpack.pack.secrets.SecretPack

/** Everything that is written on top of the pasted rooms, as one ordered list (pure, tested without a world). */
object RegionEdits {
    private const val AIR = "minecraft:air"

    /**
     * The builder's marker signs (Hypixel's build metadata, `marker: true` in the rooms pack) are not content: the cell is air.
     * FORMAT-rooms 7.4: the runtime must not place or render them.
     */
    fun markerRemovals(rooms: List<RoomInstance>): List<Edit> = buildList {
        for (r in rooms) for (b in r.design.blockEntities) {
            if (!b.marker) continue
            if (!r.volume.contains(b.pos.x, b.pos.y, b.pos.z) || r.volume.isAir(b.pos.x, b.pos.y, b.pos.z)) continue
            // A tile's centre column is what Odin hashes to name the room: nothing in it is ever changed.
            if (b.pos.x % 32 == 15 && b.pos.z % 32 == 15) continue
            val w = r.placement.toWorld(b.pos.x, b.pos.y, b.pos.z)
            add(Edit(w.x, w.y, w.z, AIR))
        }
    }

    /** The blue terracotta Hypixel leaves at one corner of a Fairy room's roof; Odin reads the room's turn from it. */
    const val CLAY = "minecraft:blue_terracotta"

    /**
     * The Fairy room's rotation marker. The recorded capture has none, so Odin's scan of the pasted room finds no clay, falls back to
     * the doors and reads the room as turned West. On Hypixel the clay stands at the roof (y [com.engineerclient.dungeonpack.pack.rooms.RoomDesign.roofY])
     * on the room's own canonical corner (0, 0): the tile's north-west corner for a room turned South.
     */
    fun fairyMarkers(rooms: List<RoomInstance>): List<Edit> = buildList {
        for (r in rooms) {
            if (!r.design.type.equals("fairy", ignoreCase = true) || r.design.roofY <= 0) continue
            val w = r.placement.toWorld(0, r.design.roofY, 0)
            add(Edit(w.x, w.y, w.z, CLAY))
        }
    }

    /** Blocks a secret is made of: they come by the run's events, so one baked into a capture must not stand before its spawn. */
    private val SECRET_BLOCKS = setOf("chest", "trapped_chest", "lever", "player_head", "player_wall_head")

    /**
     * The secrets baked into the volumes (a capture's chests, locked and trapped chests, essence heads, levers), at each site's spot and
     * the rooms pack's old spot of a moved one: air. The secrets module places them ([com.engineerclient.dungeonpack.secrets.Secrets]).
     */
    fun secretRemovals(rooms: List<RoomInstance>, secrets: SecretPack = SecretPack.bundled): List<Edit> = buildList {
        for (r in rooms) {
            val d = secrets.design(r.design.id) ?: continue
            val cells = d.sites.flatMap { listOfNotNull(it.pos, it.was) } + d.levers.map { it.pos } + d.key?.skulls?.map { it.first }.orEmpty()
            for (c in cells.distinct()) {
                if (!r.volume.contains(c.x, c.y, c.z)) continue
                if (r.volume.stateAt(c.x, c.y, c.z).substringBefore('[').removePrefix("minecraft:") !in SECRET_BLOCKS) continue
                // The centre column Odin hashes is never changed (as [markerRemovals]).
                if (c.x % 32 == 15 && c.z % 32 == 15) continue
                val w = r.placement.toWorld(c.x, c.y, c.z)
                add(Edit(w.x, w.y, w.z, AIR))
            }
        }
    }

    /**
     * Tripwire hooks that keep one WORLD facing whatever way their room is turned (DR, the 140 matched F7 runs: every rotation seen gives
     * the same facing; all other hooks turn with their room). Canonical cell -> world facing. Entrance_Closed: 96 of 96 room placements
     * in three rotations (and NORTH, where the turn gives the same); Red_Green 8 in three; Ritual 13 in two; Purple_Flags 13 in two;
     * Deathmite 26 in two.
     */
    val FIXED_HOOKS: Map<String, List<Pair<IntArray, String>>> = mapOf(
        "Entrance_Closed" to listOf(intArrayOf(14, 76, 23) to "north", intArrayOf(16, 76, 23) to "north"),
        "Red_Green" to listOf(intArrayOf(16, 83, 12) to "south", intArrayOf(16, 83, 18) to "north"),
        "Ritual" to listOf(intArrayOf(24, 66, 11) to "south", intArrayOf(24, 66, 9) to "north", intArrayOf(6, 66, 11) to "south", intArrayOf(6, 66, 9) to "north"),
        "Purple_Flags" to listOf(intArrayOf(16, 62, 26) to "north", intArrayOf(9, 62, 26) to "north"),
        "Deathmite" to listOf(intArrayOf(28, 67, 15) to "east"),
    )

    /** [FIXED_HOOKS] as edits: the pack's hook with its world facing, not turned. */
    fun fixedHooks(rooms: List<RoomInstance>): List<Edit> = buildList {
        for (r in rooms) for ((c, facing) in FIXED_HOOKS[r.design.id].orEmpty()) {
            if (!r.volume.contains(c[0], c[1], c[2])) continue
            val state = r.volume.stateAt(c[0], c[1], c[2]).takeIf { "tripwire_hook" in it } ?: continue
            val w = r.placement.toWorld(c[0], c[1], c[2])
            add(Edit(w.x, w.y, w.z, state.replace(Regex("facing=\\w+"), "facing=$facing")))
        }
    }

    /** A design's blocks in the gap column beside it ([com.engineerclient.dungeonpack.pack.doors.DoorPack.gapWalls]), turned with the room. */
    fun gapWalls(rooms: List<RoomInstance>, pack: com.engineerclient.dungeonpack.pack.doors.DoorPack = com.engineerclient.dungeonpack.pack.doors.DoorPack.bundled): List<Edit> = buildList {
        for (r in rooms) for (c in pack.gapWalls[r.design.id].orEmpty()) {
            val w = r.placement.toWorld(c.x, c.y, c.z)
            add(Edit(w.x, w.y, w.z, c.state, r.placement.quarterTurns))
        }
    }

    /** Marker removals, the baked secrets, the Fairy marker, the fixed hooks, the gap walls, then the doors ([DoorPlanner.plan]). */
    fun build(rooms: List<RoomInstance>, doors: Map<Slot, DoorKind>, seed: Long = 0L): List<Edit> =
        markerRemovals(rooms) + secretRemovals(rooms) + fairyMarkers(rooms) + fixedHooks(rooms) + gapWalls(rooms) + DoorPlanner.plan(rooms, doors, seed)
}
