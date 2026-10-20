package com.engineerclient.p3sim.run.api

import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.KeyKind
import com.engineerclient.p3sim.run.Run
import java.util.UUID

/** What a click on a door did. */
enum class DoorOpenResult { OPENED, NO_KEY, ALREADY_OPEN, NOT_A_DOOR }

/**
 * Which rooms drop which key (FORMAT-runstate 4, rule 4): every path room except the entrance, the Fairy room and the last
 * one drops a Wither Key when its last required mob dies; the last path room drops the Blood Key. A pure function of the plan.
 */
class KeyPlan(
    /** Room indices that drop a wither key, in path order. */
    val witherRooms: List<Int>,
    /** The room that drops the blood key (the last path room), or null. */
    val bloodRoom: Int?,
) {
    fun keyFor(room: Int): KeyKind? = when {
        room == bloodRoom -> KeyKind.BLOOD
        room in witherRooms -> KeyKind.WITHER
        else -> null
    }
}

/**
 * Doors, keys and room check states. Implemented by [com.engineerclient.p3sim.clear.state.DoorsModule].
 * Server thread only.
 */
interface DoorService {
    /** The key plan of a dungeon (pure). */
    fun keyPlan(plan: DungeonPlan): KeyPlan = KeyPlan(emptyList(), null)

    /** The key this room drops when its last required mob dies, or null. The mobs module asks this on a kill. */
    fun keyForRoom(run: Run, roomIndex: Int): KeyKind? = null

    /** Drops the key stand at (x, z) in [roomIndex] (y from the pack: 68.531 wither, 67.531 blood). Posts `KeyDropped`. */
    fun dropKey(run: Run, roomIndex: Int, x: Double, z: Double) {}

    /** A replay: [by] takes a key of [kind] on the recorded tick: the oldest such stand, or one dropped at ([x], [z]) in [roomIndex] when none lies. False when there is no key to give. */
    fun pickUpKey(run: Run, by: java.util.UUID, kind: KeyKind, roomIndex: Int?, x: Double, z: Double): Boolean = false

    /** A player used a door (right click on its blocks with a key in the party count): opens it, or says there is no key. */
    fun tryOpen(run: Run, door: Int, by: UUID?): DoorOpenResult = DoorOpenResult.NOT_A_DOOR

    /**
     * A swing at the block (a left click, which never reaches Fabric's attack callback in adventure mode). On a closed key door it is a use
     * of the door, as on Hypixel: it opens with a key in the party, or says the no-key line. True when the door stays shut (the hit ends
     * there: no Dungeonbreaker line).
     */
    fun touchBlock(run: Run, x: Int, y: Int, z: Int, by: UUID?): Boolean = false
    /** The door whose 3x4x3 volume contains the block, or null. */
    fun doorAtBlock(run: Run, x: Int, y: Int, z: Int): Int? = null
}
