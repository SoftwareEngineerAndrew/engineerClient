package com.engineerclient.p3sim.clear.bots

/**
 * What the executor needs from the world to do a plan's acts. The module implements it over the real services (mobs, secrets, doors,
 * puzzles); the tests implement it with a recording fake. Every call is idempotent: asking again after it is done is harmless.
 */
interface BotWorld {
    /**
     * Opens [door] for [seat]. Returns true when the door is open or falling (nothing more to do), false when it cannot be opened
     * yet (no key in the party): the executor asks again a few ticks later, and never spams the door while there is no key.
     */
    fun openDoor(seat: Int, door: Int): Boolean

    /** Brings the room's required mobs to at least [fraction] (0..1) killed by [seat]. True when none is left alive (or there are none). */
    fun killRequired(seat: Int, room: Int, fraction: Double): Boolean

    /** Brings the room's secrets to at least [fraction] (0..1) found by [seat]. True when every secret of the room is found. */
    fun findSecrets(seat: Int, room: Int, fraction: Double): Boolean

    /** Solves the room's puzzle. True when it is solved (or there is none). */
    fun solvePuzzle(seat: Int, room: Int): Boolean

    /** Whether somebody else already finished the room's mobs (white) or everything (green). */
    fun roomDone(room: Int, green: Boolean): Boolean

    /** A human stands in the room. */
    fun humanInside(room: Int): Boolean
}

/**
 * Plays a [ClearPlan]: positions for any tick and the acts done on schedule through a [BotWorld]. An act that is overdue because a
 * door has no key, or because the human stands in the room, waits (at most [graceTicks] for the human). Pure and deterministic.
 */
class PlanExecutor(plan: ClearPlan, private val world: BotWorld, private val graceTicks: Int = BotTuning.HUMAN_ROOM_GRACE_TICKS) {
    var plan: ClearPlan = plan
        private set

    private class ActState(val act: Act) {
        var done = false
        var nextTry = Int.MIN_VALUE
        /** An [OpenDoor] whose seat has stood beside the door: from then on only the key is awaited, wherever the seat has gone since. */
        var reached = false
    }

    private var states: List<ActState> = emptyList()

    /** Finished acts by kind and room, kept across a re-plan so a new plan does not repeat them. */
    private val finished = HashSet<String>()

    init { load(plan) }

    private fun key(a: Act): String = when (a) {
        is OpenDoor -> "door:${a.door}"
        is ClearMobs -> "white:${a.room}"
        is FindSecrets -> "green:${a.room}"
        is SolvePuzzle -> "puzzle:${a.room}"
    }

    private fun load(p: ClearPlan) {
        plan = p
        states = p.tracks.flatMap { it.acts }.map { ActState(it).also { s -> if (key(s.act) in finished) s.done = true } }
            .sortedWith(compareBy({ it.act.t }, { it.act.seat }))
    }

    /** Swaps in a re-plan. Acts already finished are not done twice. */
    fun replace(p: ClearPlan) = load(p)

    /** Where [seat] stands at [t] by the plan (the tracks of bots only). */
    fun positionOf(seat: Int, t: Int): Pt? = plan.track(seat)?.positionAt(t)

    /** The room work [seat] is in the middle of at [t] (killing the mobs, finding the secrets, solving the puzzle), or null. A door is not room work. */
    fun workOf(seat: Int, t: Int): Act? =
        states.firstOrNull { !it.done && it.act.seat == seat && it.act !is OpenDoor && it.act.t <= t && t <= it.act.until }?.act

    /** The acts not done yet. */
    fun pending(): List<Act> = states.filter { !it.done }.map { it.act }

    /** Does the work that is due at tick [t]. */
    fun tick(t: Int) {
        for (s in states) {
            if (s.done) continue
            val a = s.act
            if (a.t > t) continue
            if (t < s.nextTry) continue
            when (a) {
                is OpenDoor -> {
                    // A late walker delays the door; it does not open it from across the room. (A plan that never brings the seat there opens it after a while.)
                    if (!s.reached && a.spot != null && t < a.t + OPEN_GATE_MAX) {
                        val here = positionOf(a.seat, t)
                        if (here != null && here.dist(a.spot) > BotTuning.OPEN_REACH) { s.nextTry = t + RETRY_TICKS; continue }
                    }
                    s.reached = true
                    if (world.openDoor(a.seat, a.door)) finish(s) else s.nextTry = t + RETRY_TICKS
                }
                is ClearMobs -> window(s, t, a.t, a.until, a.room, false) { f -> world.killRequired(a.seat, a.room, f) }
                is FindSecrets -> window(s, t, a.t, a.until, a.room, true) { f -> world.findSecrets(a.seat, a.room, f) }
                is SolvePuzzle -> {
                    if (t < a.until) continue
                    if (world.roomDone(a.room, true) || world.solvePuzzle(a.seat, a.room)) finish(s) else s.nextTry = t + RETRY_TICKS
                }
            }
        }
    }

    private fun window(s: ActState, t: Int, from: Int, until: Int, room: Int, green: Boolean, work: (Double) -> Boolean) {
        if (world.roomDone(room, green)) { finish(s); return }
        val f = if (until <= from) 1.0 else ((t - from).toDouble() / (until - from)).coerceIn(0.0, 1.0)
        // The human in the room holds the last of it back: they may be doing it themselves. Until the grace runs out.
        if (t >= until && world.humanInside(room) && t < until + graceTicks) { s.nextTry = t + RETRY_TICKS; return }
        val partial = if (t >= until) 1.0 else f
        if (work(partial) && t >= until) finish(s) else if (partial >= 1.0) s.nextTry = t + RETRY_TICKS
    }

    private fun finish(s: ActState) { s.done = true; finished += key(s.act) }

    /** Forgets what is finished (a new run, or a restore to an earlier moment). */
    fun forgetFinished() { finished.clear(); load(plan) }

    companion object {
        const val RETRY_TICKS = 5

        /** Ticks past its time that a door waits for its opener to come within reach before it opens anyway (a plan that lost the opener must not lock the dungeon). */
        const val OPEN_GATE_MAX = 600
    }
}
