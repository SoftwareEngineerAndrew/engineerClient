package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.run.plugin.BloodAssignment
import com.engineerclient.p3sim.run.plugin.Seat

/*
 * The ClearPlan: who clears which room when, who opens which door when, and where each seat stands at every tick. It is pure data
 * (no Minecraft), built by [ClearPlanner], played by [PlanExecutor] and re-built by the planner when the player changes things.
 * All ticks are run ticks (`Run.t`: since T0, lag removed; the lag layer stretches the real time, never these numbers).
 */

/**
 * One straight piece of a seat's path: from [a] at [t0] to [b] at [t1]. Standing still is a leg with a == b. [hop] means the leg
 * is too fast to walk (its schedule needs more than a walking bot can do) and is played as blink hops of [BotTuning.HOP_BLOCKS].
 */
class Leg(val t0: Int, val t1: Int, val a: Pt, val b: Pt, val hop: Boolean = false) {
    init { require(t1 >= t0) { "a leg cannot end before it starts" } }

    /** Where the seat is at tick [t] (clamped to the leg). */
    fun at(t: Int): Pt {
        if (t <= t0 || a == b) return if (t >= t1) b else a
        if (t >= t1) return b
        val f = (t - t0).toDouble() / (t1 - t0)
        if (!hop) return a.lerp(b, f)
        val len = a.dist(b)
        val done = Math.floor(len * f / BotTuning.HOP_BLOCKS) * BotTuning.HOP_BLOCKS
        return a.lerp(b, (done / len).coerceIn(0.0, 1.0))
    }
}

/** What a seat does at a tick, besides moving. Windows run from [t] to [until]; the executor does the work spread over them. */
sealed interface Act {
    val seat: Int
    /** First tick of the act. */
    val t: Int
    /** Last tick of the window (the act is complete at this tick); equal to [t] for a single action. */
    val until: Int
}

/**
 * Opens paid door [door] (a Wither or Blood door) with the party's key; retried until it opens. When [spot] is set (the middle of the door's
 * gap) the executor holds the act until the seat's planned position is within [BotTuning.OPEN_REACH] of it: a door is opened from beside it.
 */
data class OpenDoor(override val seat: Int, override val t: Int, val door: Int, val spot: Pt? = null) : Act { override val until: Int get() = t }

/** Kills the room's required mobs, spread over the window, so the last one dies at [until] (the room turns WHITE and its key drops). */
data class ClearMobs(override val seat: Int, override val t: Int, override val until: Int, val room: Int) : Act

/** Finds the room's secrets, spread over the window, so the last one is found at [until] (the room turns GREEN). */
data class FindSecrets(override val seat: Int, override val t: Int, override val until: Int, val room: Int) : Act

/** Solves the room's puzzle at [until] (the puzzles module does the rest). */
data class SolvePuzzle(override val seat: Int, override val t: Int, override val until: Int, val room: Int) : Act

/** The seat's moving and doing for the whole plan. */
class SeatTrack(val seat: Int, val legs: List<Leg>, val acts: List<Act>) {
    /** Where the seat is at tick [t]: before the first leg, at its start; after the last, where it ended. */
    fun positionAt(t: Int): Pt {
        if (legs.isEmpty()) return Pt(0.0, 0.0)
        if (t <= legs.first().t0) return legs.first().a
        if (t >= legs.last().t1) return legs.last().b
        var lo = 0; var hi = legs.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (legs[mid].t0 <= t) lo = mid else hi = mid - 1 }
        return legs[lo].at(t)
    }

    /** The end of the track. */
    val endT: Int get() = legs.lastOrNull()?.t1 ?: 0
}

/** How a room is cleared, which decides the acts. */
enum class RoomWork { FULL, GREEN_ONLY, PUZZLE, WHITE_ONLY }

/** What the plan says about one room. [soft]: it belongs to the human seat, so no bot walks to it until the cover timer says so. */
class RoomPlan(
    val room: Int,
    val seat: Int,
    val work: RoomWork,
    val arriveT: Int,
    val whiteT: Int?,
    val greenT: Int?,
    val soft: Boolean,
)

/** A paid door's schedule: [seat] opens it at [openT]; the key drops at [keyT] in a room whose last mob [keySeat] kills (the opener itself, or another bot). */
class DoorPlan(val door: Int, val seat: Int, val keyT: Int, val openT: Int, val keySeat: Int = seat)

/** The ticks the plan implies for the run's milestones (the blood module owns the camp itself; these are for the headless gate and for bots' timing). */
class PlanMilestones(
    val bloodKeyT: Int,
    val bloodOpenT: Int,
    /** The Watcher's "proven" line and the first boss line, drawn from the pack's milestone tables (not a mechanism). */
    val watcherDoneT: Int,
    val maxorT: Int,
    /** The seat routed to the blood room, or null. */
    val bloodSeat: Int?,
)

/** A whole plan. */
class ClearPlan(
    val seats: List<Seat>,
    val tracks: List<SeatTrack>,
    val rooms: Map<Int, RoomPlan>,
    val doors: Map<Int, DoorPlan>,
    val milestones: PlanMilestones,
    /** The run tick the plan was built at (0 for the first plan). */
    val builtAt: Int,
    val assignment: BloodAssignment,
) {
    fun track(seat: Int): SeatTrack? = tracks.firstOrNull { it.seat == seat }

    /** The seat the plan gives a room to, or null. */
    fun ownerOf(room: Int): Int? = rooms[room]?.seat

    /** Rooms done (white or better) at [t] by the plan, tile-weighted by [tiles]; the entrance never counts. */
    fun tilesWhiteBy(t: Int, tiles: (Int) -> Int): Int = rooms.values.filter { (it.whiteT ?: it.greenT ?: Int.MAX_VALUE) <= t }.sumOf { tiles(it.room) }

    fun roomsGreenBy(t: Int): Int = rooms.values.count { (it.greenT ?: Int.MAX_VALUE) <= t }
}
