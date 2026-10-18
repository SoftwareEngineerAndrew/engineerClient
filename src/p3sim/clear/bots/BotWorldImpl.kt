package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.CheckMark
import com.engineerclient.p3sim.run.DamageSource
import com.engineerclient.p3sim.run.DoorPhase
import com.engineerclient.p3sim.run.ModuleEvent
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.SecretKind
import com.engineerclient.p3sim.run.api.DoorOpenResult
import com.engineerclient.p3sim.run.api.Services
import java.util.UUID

/** What a bot's kill looks and sounds like from outside; the module plays it ([BotsModule]). Called on the server thread. */
fun interface BotFx {
    /** [seat] killed a mob at ([x], [y], [z]). */
    fun killed(seat: Int, x: Double, y: Double, z: Double)
}

/** Pure mapping from the rooms pack's secret `type` words to the run's [SecretKind]. */
object SecretKinds {
    fun of(type: String?): SecretKind = when (type) {
        "item" -> SecretKind.ITEM
        "bat" -> SecretKind.BAT
        "essence" -> SecretKind.ESSENCE
        "redstoneKey" -> SecretKind.REDSTONE_KEY
        "lockedChest" -> SecretKind.LOCKED_CHEST
        else -> SecretKind.CHEST
    }
}

/**
 * The bots' hands: [BotWorld] over the run's services. A bot opens a door through the doors module (only when the party holds the key), kills a
 * room's required mobs through the damage service (so every kill is a real `MobKilled`, which turns the room WHITE and drops its key), finds
 * a room's secrets through the secrets service, and solves a puzzle through the puzzle host. When a service is not answering (a module not yet
 * filled in, a room whose mobs were never spawned) the room's mark is asked for with a `ModuleEvent` the doors module can act on
 * (see docs/ads/bots.md); that is the "synthesise room clears" path of FORMAT-runstate 6.2. Server thread only.
 */
class BotWorldImpl(private val run: Run, private val roster: Roster, private val rooms: BotRooms, private val fx: BotFx? = null) : BotWorld {
    private val state get() = run.state
    private fun uuid(seat: Int): UUID? = roster.uuids[seat]

    private val announced = HashSet<String>()
    private val creditedSpots = HashMap<Int, Int>()
    private val stuckSince = HashMap<Int, Int>()

    /** A teammate that is a ghost does nothing until it is back (its work goes to the others at the next re-plan). */
    private fun dead(seat: Int): Boolean = uuid(seat)?.let { state.players[it]?.ghost } == true

    override fun openDoor(seat: Int, door: Int): Boolean {
        if (dead(seat)) return false
        val rec = state.door(door) ?: return true
        if (rec.phase != DoorPhase.CLOSED) return true
        when (rec.plan.kind) {
            DoorKind.WITHER -> if (state.keys.witherHeld <= 0) return false
            DoorKind.BLOOD -> if (!state.keys.bloodHeld) return false
            else -> {}
        }
        return when (Services.doors.tryOpen(run, door, uuid(seat))) {
            DoorOpenResult.OPENED, DoorOpenResult.ALREADY_OPEN -> true
            DoorOpenResult.NO_KEY -> false
            DoorOpenResult.NOT_A_DOOR -> true
        }
    }

    override fun killRequired(seat: Int, room: Int, fraction: Double): Boolean {
        if (dead(seat)) return false
        val rr = state.room(room) ?: return true
        if (rr.check == CheckMark.WHITE || rr.check == CheckMark.GREEN) return true
        val by = uuid(seat)
        val required = Services.mobs.inRoom(room).filter { it.required }
        val total = maxOf(rr.mobsRequired, required.size)
        val alive = required.filter { it.alive }
        val want = Math.ceil(fraction.coerceIn(0.0, 1.0) * total).toInt()
        var killed = total - alive.size
        for (m in alive) {
            if (killed >= want) break
            // Enough damage to take any mob's HP: the kill goes through the damage service like any other.
            Services.damage.hurtMob(run, m.id, maxOf(m.hp, m.maxHp, 1.0) * 2.0, DamageSource.MELEE, by)
            fx?.killed(seat, m.x, m.y, m.z)
            killed++
        }
        val left = Services.mobs.inRoom(room).count { it.required && it.alive }
        if (fraction >= 1.0 && left == 0) {
            // Nothing is left to kill. The state module turns the room WHITE on the last kill; where nothing was ever there to kill, ask for it.
            if (state.room(room)?.check?.let { it != CheckMark.WHITE && it != CheckMark.GREEN } == true) tell("white", room, seat)
        }
        return left == 0
    }

    override fun findSecrets(seat: Int, room: Int, fraction: Double): Boolean {
        if (dead(seat)) return false
        val rr = state.room(room) ?: return true
        val max = rr.secretsMax
        if (max <= 0 || rr.secretsFound >= max) return true
        val want = Math.ceil(fraction.coerceIn(0.0, 1.0) * max).toInt()
        if (rr.secretsFound >= want) return false
        val by = uuid(seat)
        val spots = rooms.secretSpots(room)
        var i = creditedSpots[room] ?: 0
        while (rr.secretsFound < want && i < spots.size) {
            val s = spots[i++]
            Services.secrets.found(run, room, SecretKinds.of(s.secret.type), by, s.x, s.y, s.z)
        }
        creditedSpots[room] = i
        if (rr.secretsFound >= max) return true
        if (fraction >= 1.0 && i >= spots.size) {
            // The room has fewer placeable secrets than it prints (the pack's auto-credit covers the gap); if it does not, ask for the rest after a moment.
            val since = stuckSince.getOrPut(room) { run.t }
            if (run.t - since >= STUCK_TICKS) tell("green", room, seat)
        }
        return rr.secretsFound >= max
    }

    override fun solvePuzzle(seat: Int, room: Int): Boolean {
        if (dead(seat)) return false
        val rec = state.room(room) ?: return true
        if (rec.check == CheckMark.GREEN) return true
        val puzzle = Services.puzzles.puzzleOf(run, room)
        if (puzzle != null) {
            if (puzzle.status == PuzzleStatus.UNSOLVED || puzzle.status == PuzzleStatus.FAILED) Services.puzzles.reportSolved(puzzle, uuid(seat))
            return true
        }
        tell("puzzle", room, seat)
        return true
    }

    override fun roomDone(room: Int, green: Boolean): Boolean {
        val c = state.room(room)?.check ?: return true
        return if (green) c == CheckMark.GREEN else c == CheckMark.WHITE || c == CheckMark.GREEN
    }

    override fun humanInside(room: Int): Boolean {
        val rr = state.room(room) ?: return false
        val humans = roster.humans.mapNotNull { uuid(it.index) }
        return rr.occupants.any { it in humans }
    }

    /** Asks the doors module (which owns the marks) for a room mark the services could not produce, once per room and kind. */
    private fun tell(kind: String, room: Int, seat: Int) {
        if (!announced.add("$kind:$room")) return
        run.bus.post(ModuleEvent(run.t, "bots", "clear.$kind", mapOf("room" to room, "seat" to seat, "player" to uuid(seat)?.toString())))
    }

    /** Forgets progress (a savepoint restore takes the run back). */
    fun reset() { announced.clear(); creditedSpots.clear(); stuckSince.clear() }

    companion object {
        /** Ticks the secrets service gets to finish a room by itself before the bot asks for the rest. */
        const val STUCK_TICKS = 100
    }
}

/** The room facts the world needs, from the plan (so tests and the module can supply them). */
class BotRooms(private val plan: com.engineerclient.p3sim.run.DungeonPlan) {
    private val cache = HashMap<Int, List<SecretSpot>>()
    fun secretSpots(room: Int): List<SecretSpot> = cache.getOrPut(room) { plan.rooms.firstOrNull { it.index == room }?.let { RoomRoutes.secretSpots(it) }.orEmpty() }
}
