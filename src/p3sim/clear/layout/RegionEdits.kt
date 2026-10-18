package com.engineerclient.p3sim.clear.layout

import com.engineerclient.p3sim.pack.layouts.DoorKind

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
     * the doors and reads the room as turned West. On Hypixel the clay stands at the roof (y [com.engineerclient.p3sim.pack.rooms.RoomDesign.roofY])
     * on the room's own canonical corner (0, 0): the tile's north-west corner for a room turned South.
     */
    fun fairyMarkers(rooms: List<RoomInstance>): List<Edit> = buildList {
        for (r in rooms) {
            if (!r.design.type.equals("fairy", ignoreCase = true) || r.design.roofY <= 0) continue
            val w = r.placement.toWorld(0, r.design.roofY, 0)
            add(Edit(w.x, w.y, w.z, CLAY))
        }
    }

    /** Marker removals, the Fairy marker, then the doors ([DoorPlanner.plan]). */
    fun build(rooms: List<RoomInstance>, doors: Map<Slot, DoorKind>, seed: Long = 0L): List<Edit> =
        markerRemovals(rooms) + fairyMarkers(rooms) + DoorPlanner.plan(rooms, doors, seed)
}
