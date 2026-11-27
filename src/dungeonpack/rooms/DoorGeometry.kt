package com.engineerclient.dungeonpack.rooms

import com.engineerclient.dungeonpack.pack.DoorModel
import com.engineerclient.dungeonpack.pack.DungeonFrame
import com.engineerclient.dungeonpack.pack.layouts.DoorKind

/** One of the 60 door slots (FORMAT-layouts 2): `axis` `'E'` joins `(tx,tz)` and `(tx+1,tz)`, `'S'` joins `(tx,tz)` and `(tx,tz+1)`. */
data class Slot(val axis: Char, val tx: Int, val tz: Int)

/**
 * One block to write on top of the pasted rooms: [state] is a canonical state string, to be turned [quarterTurns] clockwise (0 for a state
 * that is already in the world frame). A player head carries its [texture] hash (textures.minecraft.net/texture/<hash>).
 */
class Edit(val x: Int, val y: Int, val z: Int, val state: String, val quarterTurns: Int = 0, val texture: String? = null)

/** A block as the rooms hold it: a canonical [state] and the [quarterTurns] that put it in the world (as in [Edit]). */
data class Block(val state: String, val quarterTurns: Int = 0)

/**
 * Where a doorway is, in blocks. Pure; no Minecraft types, so the layout's door rules are unit-tested headless.
 *
 * A tile side has its opening centred on the tile (FORMAT-rooms 7.3): three cells wide (offsets 14 to 16 along the side), four
 * tall (y 69 to 72); the frame columns stand at offsets 13 and 17, the lintel is y 73 and the threshold y 68. Between two tiles lies
 * the 1-block gap column (`min + 31`) that no room volume covers; the door body (DoorModel) is that column plus the edge plane
 * of both rooms. A tile side is named by the compass side of the WORLD (N is toward -z, E toward +x).
 */
object DoorGeometry {

    private const val SIDES = "NESW"

    /** [side] turned [quarterTurns] clockwise as seen from above (N -> E -> S -> W). */
    fun turn(side: Char, quarterTurns: Int): Char {
        val i = SIDES.indexOf(side)
        require(i >= 0) { "side must be N, E, S or W, not '$side'" }
        return SIDES[(i + quarterTurns).mod(4)]
    }

    /** The slot on [side] of world tile ([tx], [tz]), or null at the edge of the grid where there is none (outside the 60 slots). */
    fun slotOf(tx: Int, tz: Int, side: Char): Slot? {
        val s = when (side) {
            'E' -> Slot('E', tx, tz)
            'W' -> Slot('E', tx - 1, tz)
            'S' -> Slot('S', tx, tz)
            'N' -> Slot('S', tx, tz - 1)
            else -> throw IllegalArgumentException("side must be N, E, S or W, not '$side'")
        }
        return s.takeIf { DungeonFrame.isSlot(it.axis, it.tx, it.tz) }
    }

    /** The closed door body of [kind] on [slot] (36 blocks around the slot's centre), or nothing for a doorway without one. */
    fun bodyEdits(slot: Slot, kind: DoorKind): List<Edit> {
        val block = DoorModel.bodyBlock(kind) ?: return emptyList()
        val (cx, cz) = DungeonFrame.doorSpot(slot.axis, slot.tx, slot.tz)
        return DoorModel.bodyCells(cx, cz).map { Edit(it.x, it.y, it.z, block) }
    }
}
