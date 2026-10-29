package com.engineerclient.dungeonpack.rooms

import com.engineerclient.dungeonpack.pack.doors.DoorPack
import com.engineerclient.dungeonpack.pack.doors.PatchCell
import com.engineerclient.dungeonpack.pack.layouts.DoorKind
import com.engineerclient.dungeonpack.pack.rooms.Placement
import com.engineerclient.dungeonpack.pack.rooms.RoomDesign
import com.engineerclient.dungeonpack.pack.rooms.RoomVolume

/** One room that is pasted: its design, the volume to paste (base or a variant) and where it stands. */
class RoomInstance(val design: RoomDesign, val volume: RoomVolume, val placement: Placement) {
    /** The canonical state string of the block at a WORLD cell, or null when the cell is outside the room's box or air. */
    fun stateAt(x: Int, y: Int, z: Int): String? {
        val (cx, cz) = placement.toCanonical(x, z) ?: return null
        if (!volume.contains(cx, y, cz)) return null
        return volume.stateAt(cx, y, cz).takeIf { it != RoomVolume.AIR }
    }

    fun isSolid(x: Int, y: Int, z: Int): Boolean = stateAt(x, y, z) != null
}

/**
 * Turns a layout's doors into block edits on top of the pasted rooms, as Hypixel builds them (ads-handoffs results/room-doors.md):
 *
 *  - every room side takes its recorded look from the [DoorPack]: `open` where the layout connects it, `closed` where it does not
 *    (`closedEstimated` for a side never seen closed, the base volume where nothing is known). A connected side never seen open is its
 *    closed look: on Hypixel open = closed + the door unit, nothing else is carved;
 *  - every connected slot gets one door unit, its floor and its body ([DoorUnits]).
 *
 * Pure, so it is tested without a world: the result is a list of [Edit]s in the order they apply. The neighbour shape updates that follow
 * the paste need a world and are done by the chunk writer over [DoorUnits.shapeCells].
 *
 * ESTIMATED: 185 sides never recorded without a door use `closedEstimated` (about 70 percent of their unit cells right when the rule is
 * checked on recorded sides); 8 designs never recorded use their base volume.
 */
object DoorPlanner {
    /**
     * The edits that turn the pasted [rooms] into the layout's doors. [doors] maps a slot to its final kind; a slot that is absent,
     * `NONE` or `UNKNOWN` is a wall. Room sides first, then the doors in slot order.
     */
    fun plan(rooms: List<RoomInstance>, doors: Map<Slot, DoorKind>, seed: Long = 0L, pack: DoorPack = DoorPack.bundled): List<Edit> {
        val out = ArrayList<Edit>()
        val looks = sides(rooms, doors, pack, out)
        for ((slot, kind) in doors.entries.sortedWith(compareBy({ it.key.axis }, { it.key.tz }, { it.key.tx }))) {
            if (!kind.connected) continue
            out += DoorUnits.edits(pack, slot, kind, seed, looks::keepAt, looks::floorAt)
        }
        return out
    }

    /** y 68 of the room sides by world cell, as the door floors read it: [floorAt] as pasted, [keepAt] as the room is without a door. */
    class FloorLooks internal constructor(private val rooms: List<RoomInstance>) {
        internal val pasted = HashMap<Long, Block>()
        internal val closed = HashMap<Long, Block>()
        fun floorAt(x: Int, z: Int): Block? = pasted[cellKey(x, z)] ?: base(x, z)
        fun keepAt(x: Int, z: Int): Block? = closed[cellKey(x, z)] ?: base(x, z)
        private fun base(x: Int, z: Int): Block? =
            rooms.firstNotNullOfOrNull { r -> r.stateAt(x, DoorUnits.FLOOR_Y, z)?.let { Block(it, r.placement.quarterTurns) } }
    }

    /** The y 68 looks of [rooms] under [doors], as [plan] gives them to the door floors (for tests). */
    fun looks(rooms: List<RoomInstance>, doors: Map<Slot, DoorKind>, pack: DoorPack = DoorPack.bundled): FloorLooks = sides(rooms, doors, pack, null)

    /** Every room side's recorded look, open where the layout connects it, else closed, written to [out] when given. */
    private fun sides(rooms: List<RoomInstance>, doors: Map<Slot, DoorKind>, pack: DoorPack, out: MutableList<Edit>?): FloorLooks {
        val looks = FloorLooks(rooms)
        for (room in rooms) {
            val p = room.placement
            for (slot in room.design.slots) {
                val wt = p.worldTileOf(slot.tile.x, slot.tile.z)
                val gap = DoorGeometry.slotOf(wt.x, wt.z, DoorGeometry.turn(slot.side, p.quarterTurns))
                val connected = gap != null && doors[gap]?.connected == true
                val known = pack.side(room.design.id, slot.tile.x, slot.tile.z, slot.side) ?: continue
                val shut = known.closed ?: known.closedEstimated
                val cells = (if (connected) known.open else null) ?: shut ?: continue
                patch(room, cells, out, looks.pasted)
                if (connected && shut != null) patch(room, shut, null, looks.closed)
            }
        }
        return looks
    }

    /** Writes [cells] of a room side to [out] (when given) and their y 68 cells to [floor]. */
    private fun patch(room: RoomInstance, cells: List<PatchCell>, out: MutableList<Edit>?, floor: MutableMap<Long, Block>) {
        val p = room.placement
        for (c in cells) {
            val w = p.toWorld(c.x, c.y, c.z)
            out?.add(Edit(w.x, w.y, w.z, c.state, p.quarterTurns))
            if (w.y == DoorUnits.FLOOR_Y) floor[cellKey(w.x, w.z)] = Block(c.state, p.quarterTurns)
        }
    }

    private fun cellKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
}
