package com.engineerclient.p3sim.clear.state

import com.engineerclient.p3sim.run.CheckMark
import com.engineerclient.p3sim.run.RoomRecord
import com.engineerclient.p3sim.run.RoomType

/**
 * The room check-mark rules (FORMAT-runstate 6): `UNDISCOVERED -> NONE -> WHITE -> GREEN`, plus RED. WHITE is the room's required
 * objective done (its required mobs dead, its puzzle solved), GREEN is also every secret found; a room with no secrets goes
 * straight to GREEN. The Fairy room is never "none": it goes straight from undiscovered to green. Pure.
 */
object RoomChecks {
    /** The mark a room shows the first time it is discovered (a door fell beside it, a party member stepped in). */
    fun onDiscovered(type: RoomType): CheckMark = if (type == RoomType.FAIRY) CheckMark.GREEN else CheckMark.NONE

    /** The mark once a room's objective is done: GREEN when no secret is left to find, else WHITE. */
    fun onObjectiveDone(secretsMax: Int, secretsFound: Int): CheckMark =
        if (secretsMax <= 0 || secretsFound >= secretsMax) CheckMark.GREEN else CheckMark.WHITE

    /** Whether a WHITE room has all its secrets (GREEN requires `found == max`, 100 % of 5,000 measured transitions). */
    fun secretsComplete(secretsMax: Int, secretsFound: Int): Boolean = secretsMax > 0 && secretsFound >= secretsMax

    /** Rooms that count for the counters: everything but the entrance (FORMAT-runstate 6.3). */
    fun counts(type: RoomType): Boolean = type != RoomType.ENTRANCE

    /** `Opened Rooms` and `Completed Rooms` in tile units, the entrance excluded. RED does not count as completed. */
    fun counters(rooms: List<RoomRecord>): Pair<Int, Int> {
        var opened = 0
        var completed = 0
        for (r in rooms) {
            if (!counts(r.plan.type)) continue
            if (r.check != CheckMark.UNDISCOVERED) opened += r.plan.tileCount
            if (r.check == CheckMark.WHITE || r.check == CheckMark.GREEN) completed += r.plan.tileCount
        }
        return opened to completed
    }
}
