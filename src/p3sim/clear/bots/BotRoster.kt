package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.run.CheckMark
import com.engineerclient.p3sim.run.DoorPhase
import com.engineerclient.p3sim.run.RunState
import com.engineerclient.p3sim.run.plugin.Seat
import com.engineerclient.p3sim.pack.layouts.DoorKind
import java.util.UUID
import kotlin.random.Random

/**
 * Who is in the party: the human seats first, then the bots. [uuids] and [names] are by seat index; [identities] is a bot seat's [BotIdentity]
 * (empty for a human seat).
 */
class Roster(val seats: List<Seat>, val uuids: Map<Int, UUID>, val names: Map<Int, String>, val identities: Map<Int, BotIdentity> = emptyMap()) {
    val bots: List<Seat> get() = seats.filter { !it.isHuman }
    val humans: List<Seat> get() = seats.filter { it.isHuman }
    fun seatOf(uuid: UUID): Seat? = uuids.entries.firstOrNull { it.value == uuid }?.key?.let { i -> seats.firstOrNull { it.index == i } }
}

/**
 * Builds the party the clear plans for. Humans take the first seats (in the order given), the bots take the classes no human plays, in the
 * leap order the boss sim already uses; `RunConfig.partySize` caps the party (fewer bots, never fewer humans). A bot's id is the one the boss sim's
 * own bot of that class has, so the head in the leap window, the tab entry and the skin are the same before and after the boss entry. Pure.
 */
object BotRoster {
    val CLASSES = listOf("HEALER", "MAGE", "BERSERK", "ARCHER", "TANK")

    /** The levels a class-word teammate shows (a plain account, the middle of the recordings). */
    const val DEFAULT_SB_LEVEL = 326
    const val DEFAULT_CLASS_LEVEL = 48

    /** The boss sim's bot name for a class ("Healer"). */
    fun label(cls: String): String = cls.lowercase().replaceFirstChar { it.uppercase() }

    /** The profile id of the bot of a class: the same name-based id the boss sim gives its bot. */
    fun botUuid(cls: String): UUID = UUID.nameUUIDFromBytes("p3sim:${label(cls)}".toByteArray())

    /**
     * [humans] are (id, name, class or null) in seat order. [order] is the class order bots are taken in (the boss sim's leap order); classes it
     * lacks follow in the usual order. With a [random], each bot is a player of its own ([BotIdentities]: name, rank, levels, glyph, version 4
     * profile id); without one, a bot is named for its class and has the boss sim's profile id, as the bots of the boss sim are.
     */
    fun build(humans: List<Triple<UUID, String, String?>>, botsOn: Boolean, partySize: Int, order: List<String> = CLASSES, random: Random? = null): Roster {
        val seats = ArrayList<Seat>()
        val uuids = HashMap<Int, UUID>(); val names = HashMap<Int, String>(); val identities = HashMap<Int, BotIdentity>()
        val used = HashSet<String>()
        for ((i, h) in humans.withIndex()) {
            val cls = h.third?.uppercase()?.takeIf { it in CLASSES && it !in used } ?: (order + CLASSES).firstOrNull { it !in used } ?: CLASSES.first()
            used += cls
            seats += Seat(i, cls, true); uuids[i] = h.first; names[i] = h.second
        }
        if (botsOn) {
            val free = (order.map { it.uppercase() } + CLASSES).distinct().filter { it in CLASSES && it !in used }
            val n = (partySize - humans.size).coerceIn(0, free.size)
            for (k in 0 until n) {
                val cls = free[k]
                val idx = seats.size
                seats += Seat(idx, cls, false)
                val identity = if (random != null) BotIdentities.draw(random, names.values.map { it.lowercase() }.toSet())
                    else BotIdentities.classWord(cls, botUuid(cls), DEFAULT_SB_LEVEL, DEFAULT_CLASS_LEVEL)
                identities[idx] = identity; uuids[idx] = identity.uuid; names[idx] = identity.name
            }
        }
        return Roster(seats, uuids, names, identities)
    }
}

/**
 * Reads the run's hub into the [PlanState] a re-plan starts from: which rooms are done, which doors can be passed from when, where each bot
 * is, which room each bot is part-way through, and which rooms the human has taken. Pure, so a re-plan after any play can be tested without
 * a world.
 */
object ReplanState {
    /**
     * [plan] is the plan being replaced (its room windows say who is part-way through what); [seatPos] where each bot stands; [humanTouched]
     * room to the tick a human last went into it; [covered] whether the bots now cover for the human; [openerHint] the seat that is the door crew.
     */
    fun build(
        state: RunState, now: Int, plan: ClearPlan?, roster: Roster, seatPos: Map<Int, Pt>,
        humanTouched: Map<Int, Int>, covered: Boolean, openerHint: Int?, touchWindow: Int = BotTuning.COVER_AFTER_TICKS,
    ): PlanState {
        val white = HashSet<Int>(); val green = HashSet<Int>()
        for (r in state.rooms) when (r.check) {
            CheckMark.WHITE -> white += r.index
            CheckMark.GREEN -> { white += r.index; green += r.index }
            else -> {}
        }
        val passable = HashMap<Int, Int>()
        for (d in state.doors) {
            if (d.plan.kind == DoorKind.NORMAL || !d.plan.kind.connected) continue
            if (d.phase != DoorPhase.CLOSED) passable[d.index] = (d.openedAt ?: now) + BotTuning.DOOR_PASSABLE_AFTER
        }
        val humanIds = roster.humans.mapNotNull { roster.uuids[it.index] }.toSet()
        val occupied = state.rooms.filter { r -> r.occupants.any { it in humanIds } }.map { it.index }
        val touched = humanTouched.filter { (room, t) -> now - t <= touchWindow && room !in green }.keys
        val humanRooms = (occupied + touched).filter { it !in green }.toSet()
        val commits = ArrayList<Commit>()
        val free = HashMap<Int, Int>()
        for (s in roster.bots) {
            free[s.index] = now
            val mine = plan?.rooms?.values?.filter { it.seat == s.index && !it.soft && it.arriveT <= now }
                ?.filter { (it.greenT ?: it.whiteT ?: 0) > now && it.room !in green && it.room !in humanRooms }
                ?.maxByOrNull { it.arriveT }
            if (mine != null) commits += Commit(s.index, mine.room, mine.work, mine.arriveT, mine.whiteT, mine.greenT ?: mine.whiteT ?: now)
        }
        return PlanState(
            now, white, green, passable, state.keys.witherHeld, seatPos, free, commits, humanRooms,
            humanSeatActive = !covered, openerHint = openerHint,
        )
    }
}
