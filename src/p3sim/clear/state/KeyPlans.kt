package com.engineerclient.p3sim.clear.state

import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.KeyKind
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.api.KeyPlan
import com.engineerclient.p3sim.run.KeyState

/**
 * Which rooms drop which key (FORMAT-runstate 4, rule 4), a pure function of the plan. The entrance -> blood path is unique (the
 * doorways form a tree). Counting the path as `R0 = entrance ... Rn = the blood room`:
 *  - every room from R1 to R(n-2), except the Fairy room, drops one Wither Key when its last required mob dies;
 *  - R(n-1), the room right before the blood room, drops the Blood Key;
 *  - never the entrance, never the Fairy room, never the blood room itself.
 * So the paid wither doors (one more on the path than keys, the free Fairy door) are exactly paid by these keys.
 */
object KeyPlans {
    fun of(plan: DungeonPlan): KeyPlan {
        val path = plan.path
        if (path.size < 3) return KeyPlan(emptyList(), null)
        val bloodRoom = path[path.size - 2]
        // Not a dungeon the game makes (the blood key never comes from the entrance or the Fairy room): no keys rather than keys from there.
        if (plan.rooms.getOrNull(bloodRoom)?.type.let { it == RoomType.FAIRY || it == RoomType.ENTRANCE }) return KeyPlan(emptyList(), null)
        val wither = path.subList(1, path.size - 2).filter { i -> plan.rooms.getOrNull(i)?.type != RoomType.FAIRY }
        return KeyPlan(wither, bloodRoom)
    }
}

/**
 * The party-shared key count (FORMAT-runstate 4, rule 5) over [KeyState]. A key picked up adds one to the count, a door opened
 * takes one off, so keys obtained equal doors opened at the end. Which rooms have already dropped theirs is kept here too (one
 * key per room, ever). Pure.
 */
class KeyLedger(private val keys: KeyState, val plan: KeyPlan) {
    private val dropped = LinkedHashSet<Int>()

    /** The key this room still has to drop, or null (not a key room, or it already dropped). */
    fun pending(room: Int): KeyKind? = if (room in dropped) null else plan.keyFor(room)

    /** Notes that the room's key has dropped. False when it had none to drop. */
    fun markDropped(room: Int): Boolean {
        if (pending(room) == null) return false
        dropped += room
        return true
    }

    fun droppedRooms(): Set<Int> = dropped

    fun restoreDropped(rooms: Collection<Int>) { dropped.clear(); dropped.addAll(rooms) }

    /** A key was picked up. */
    fun pick(kind: KeyKind) {
        when (kind) {
            KeyKind.WITHER -> { keys.witherHeld++; keys.witherObtained++ }
            KeyKind.BLOOD -> { keys.bloodHeld = true; keys.bloodObtained = true }
        }
    }

    /** Whether a door that needs [kind] could open now. */
    fun has(kind: KeyKind): Boolean = when (kind) { KeyKind.WITHER -> keys.witherHeld > 0; KeyKind.BLOOD -> keys.bloodHeld }

    /** A door was opened with a key: takes it off the count. False (and nothing changes) when the party has none. */
    fun consume(kind: KeyKind): Boolean {
        if (!has(kind)) return false
        when (kind) {
            KeyKind.WITHER -> { keys.witherHeld--; keys.witherUsed++ }
            KeyKind.BLOOD -> { keys.bloodHeld = false; keys.bloodUsed = true }
        }
        return true
    }

    /** The invariant the measured runs hold: obtained minus used is what is held. */
    fun consistent(): Boolean =
        keys.witherObtained - keys.witherUsed == keys.witherHeld && keys.witherHeld >= 0 &&
            (!keys.bloodUsed || keys.bloodObtained) && keys.bloodHeld == (keys.bloodObtained && !keys.bloodUsed)
}
