package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.PlacedRoom
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.plugin.BloodAssignment
import com.engineerclient.p3sim.run.plugin.Seat
import kotlin.math.roundToInt
import kotlin.random.Random

/** A room a seat has already started: the plan keeps it (a re-plan never takes a room from a bot that is half way through it). */
class Commit(val seat: Int, val room: Int, val work: RoomWork, val arriveT: Int, val whiteT: Int?, val greenT: Int)

/**
 * What a (re-)plan starts from. The first plan is [initial]. A re-plan reads the run: which rooms are done, which doors are passable
 * from when, where each seat is and when it is next free.
 */
class PlanState(
    val now: Int,
    /** Rooms already WHITE (mobs dead) and rooms already GREEN. A green room needs nothing; a white one only its secrets. */
    val white: Set<Int> = emptySet(),
    val green: Set<Int> = emptySet(),
    /** Doors that are open or falling: door index to the tick from which a walker may pass. */
    val passableAt: Map<Int, Int> = emptyMap(),
    /** Wither keys the party holds now (the next closed door opens on the first of them). */
    val witherKeys: Int = 0,
    val seatPos: Map<Int, Pt> = emptyMap(),
    /** The tick each seat is next free (a seat in a room is free when it finishes it). */
    val seatFreeAt: Map<Int, Int> = emptyMap(),
    val commits: List<Commit> = emptyList(),
    /** Rooms the human has taken or stands in: nobody else is planned for them. */
    val humanRooms: Set<Int> = emptySet(),
    /** Whether the human seat still takes part in the plan (its rooms are soft). False once the bots cover for the human. */
    val humanSeatActive: Boolean = true,
    /** The seat that has been opening the doors, kept on the job through a re-plan (null: draw one). */
    val openerHint: Int? = null,
) {
    companion object {
        fun initial() = PlanState(0)
    }
}

/** Everything the planner reads. */
class PlannerInput(
    val plan: DungeonPlan,
    val tables: BotTables,
    val seats: List<Seat>,
    val random: Random,
    val assignment: BloodAssignment = BloodAssignment.NOBODY,
    val state: PlanState = PlanState.initial(),
    /** Multiplies the planning speed (setting "Bots Speed Scale"). */
    val speedScale: Double = 1.0,
    val workScale: Double = BotTuning.ROOM_WORK_SCALE,
    /** Recorded walks through rooms, when the optional pack is installed; null: every room uses the plain route. */
    val traversals: RoomTraversals? = null,
    /**
     * Where each bot seat stands at tick 0 of the first plan (the countdown ends with the bots at the entrance room's exit door, [Countdown]); a seat
     * without an entry starts on a ring round the entrance room's middle. A re-plan starts from the real positions instead.
     */
    val startAt: Map<Int, Pt> = emptyMap(),
    /** The floor of a room as a bot can walk it (from the room's blocks); null: the bots wander over the room's tiles. */
    val areas: RoomAreas? = null,
    /** Wither doors people opened since the last plan: the bots may leap to them. */
    val people: List<PersonDoor> = emptyList(),
)

/**
 * Builds a [ClearPlan] from a dungeon, the measured tables and the party: the door and key schedule first (drawn from the pack, with
 * a common pace so a fast party is fast everywhere), then the rooms by list scheduling (each free seat takes the room it can start
 * soonest, a puzzle room preferring a seat of a puzzle class), with every walk routed through the door graph and held at closed doors.
 * Deterministic from [PlannerInput.random]. Pure, fast (a plan takes a few milliseconds), and the same code re-plans mid-run.
 *
 * What it does NOT do: it never reads the Watcher stage's inner workings. The blood assignment from the party strategy says which seat
 * (if any) goes to the blood room and by when; the camp itself is the blood module's.
 */
class ClearPlanner(private val inp: PlannerInput) {
    private val plan = inp.plan
    private val geo = PlanGeometry(plan)
    private val tables = inp.tables
    private val st = inp.state
    private val rnd = inp.random
    private val pace = PlanMath.Pace(Random(rnd.nextLong()), BotTuning.PACE_CORRELATION)
    private val roomPace = PlanMath.Pace(Random(rnd.nextLong()), BotTuning.ROOM_PACE_CORRELATION)
    private val pick = Random(rnd.nextLong())
    /** The door-to-door and last-door-to-blood-key gaps: independent of the party's pace (ADR: rank correlation with the door before 0.0 to 0.17). */
    private val gaps = Random(rnd.nextLong())

    private val entrance: Int = plan.entrance?.index ?: plan.rooms.firstOrNull()?.index ?: 0
    private val fairy: Int? = plan.fairy?.index
    private val bloodRoom: Int? = plan.blood?.index

    /** From when each door may be walked through. Filled by [scheduleDoors]; plain doorways are 0. */
    private val pass = HashMap<Int, Int>()

    private class Builder(val seat: Seat, var t: Int, var pos: Pt, var room: Int) {
        val legs = ArrayList<Leg>()
        val acts = ArrayList<Act>()
        /** Work this seat must be somewhere for at a set time, earliest first (a door crew's doors, a key kill, the blood room). */
        val duties = ArrayList<Duty>()

        /** The duty that is due first, or null. */
        val duty: Duty? get() = duties.minByOrNull { it.startBy }

        fun stand(until: Int) { if (until > t) { legs += Leg(t, until, pos, pos); t = until } }
    }

    /** Work a seat must be somewhere for at a set time (the door crew, the blood room): it is done when the seat gets there in the list. */
    private class Duty(val startBy: Int, val run: (Builder) -> Unit)

    private val taskSeed = pick.nextLong()

    fun build(): ClearPlan {
        val now = st.now
        val active = inp.seats.filter { !it.isHuman || st.humanSeatActive }
        val bots = inp.seats.filter { !it.isHuman }
        val builders = active.associate { s ->
            s.index to Builder(s, maxOf(now, st.seatFreeAt[s.index] ?: now), st.seatPos[s.index] ?: inp.startAt[s.index] ?: geo.spread(geo.centre(entrance), s.index, inp.seats.size), entrance)
        }

        // ---- doors and keys ----------------------------------------------------------------------------------------
        val ms = scheduleDoors(now)
        val milestones = ms.milestones

        // ---- carried-over commitments (a seat half way through a room keeps it) -----------------------------------------
        val rooms = LinkedHashMap<Int, RoomPlan>()
        for (c in st.commits) {
            val b = builders[c.seat] ?: continue
            commitRoom(b, c, rooms)
        }

        // ---- door crew and blood assignment as duties ----------------------------------------------------------------
        val doorPlans = LinkedHashMap<Int, DoorPlan>()
        assignDoorDuties(ms, bots.filter { builders[it.index] != null }, builders, doorPlans, rooms)
        var bloodSeat: Int? = null
        inp.assignment.seat?.let { idx ->
            val s = inp.seats.firstOrNull { it.index == idx }
            val b = builders[idx]
            if (s != null && !s.isHuman && b != null && bloodRoom != null) {
                bloodSeat = idx
                val doorToBlood = plan.doors.firstOrNull { it.kind == DoorKind.BLOOD }
                val byT = maxOf(now, milestones.bloodOpenT - (doorToBlood?.let { geoTicks(b, geo.doorPt(it.index), b.room) } ?: 0))
                b.duties += Duty(byT) { bb -> campDuty(bb, milestones, inp.assignment.arriveAtT) }
            }
        }

        // ---- the rooms, by list scheduling ----------------------------------------------------------------------------
        scheduleRooms(builders, rooms, ms)

        // ---- idle seats gather at the room before the blood room ------------------------------------------------------
        val gatherRoom = plan.path.getOrNull(plan.path.size - 2) ?: entrance
        for ((i, b) in builders.values.withIndex()) {
            if (b.seat.isHuman) continue
            val dest = geo.spread(geo.centre(gatherRoom), i, builders.size)
            if (b.room != gatherRoom) walk(b, geo.route(b.pos, b.room, gatherRoom, dest), tables.moveBpt(b.seat.dungeonClass) * inp.speedScale, null)
            b.room = gatherRoom
        }

        val walked = builders.values.filter { !it.seat.isHuman }.map { SeatTrack(it.seat.index, it.legs, it.acts.sortedBy { a -> a.t }) }
        // ---- teammates leap to the door opener (after everything else, so the draws above stay as they were) -----------------
        val bloodDoor = plan.doors.firstOrNull { it.kind == DoorKind.BLOOD }?.index
        val (tracks, leaps) = Leaps.add(walked, doorPlans.values.filter { it.openT >= now }, bloodDoor, inp.seats.filter { it.isHuman }.map { it.index }.toSet(), leapWorld, Random(rnd.nextLong()), inp.people)
        // ---- and, once their rooms are done, into the blood camp ------------------------------------------------------------
        val (camped, campLeaps) = CampLeaps.add(tracks, bloodSeat, milestones.bloodOpenT, milestones.watcherDoneT, now, inp.seats.filter { it.isHuman }.map { it.index }.toSet(), plan.seed)
        return ClearPlan(inp.seats, camped, rooms, doorPlans, milestones.copy(bloodSeat = bloodSeat), now, inp.assignment, (leaps + campLeaps).sortedBy { it.t })
    }

    /** The plan's geometry for the leap pass: which doors a walk goes through, and the walk itself at the planning pace. */
    private val leapWorld = object : LeapWorld {
        override fun through(from: Pt, to: Pt, door: Int): Boolean {
            val a = geo.roomAt(from)?.index ?: return false
            val b = geo.roomAt(to)?.index ?: return false
            return a != b && door in geo.doorsOnRoute(a, b)
        }

        override fun walk(from: Pt, to: Pt, t0: Int): List<Leg> {
            // An opener stands in the door's gap, between rooms: the walk starts in the room a step towards where it goes.
            val a = (0..8).firstNotNullOfOrNull { i -> geo.roomAt(from.lerp(to, minOf(1.0, i / maxOf(1.0, from.dist(to)))))?.index } ?: return emptyList()
            val b = geo.roomAt(to)?.index ?: a
            val legs = ArrayList<Leg>()
            walkEnd(expand(geo.route(from, a, b, to)), t0, BotTuning.DEFAULT_PLAN_BPT * inp.speedScale, legs)
            return legs
        }
    }

    // ===================================================================================================================
    // doors, keys and the blood milestones

    /** Result of [scheduleDoors]. */
    private class DoorSchedule(
        val milestones: PlanMilestones,
        /** Paid doors in path order with the key room that feeds each and the planned key and open ticks. */
        val paid: List<PaidDoor>,
        val bloodDoor: Int?,
        val bloodKeyRoom: Int?,
        /** The blood door has not opened yet (a re-plan after it opened has nothing more for the crew to do). */
        val bloodPending: Boolean,
        /** Whether another bot than the opener is to kill the blood-key room's last mob (drawn with [BotTuning.KEY_SPLIT_CHANCE]). */
        val bloodSplit: Boolean = false,
    )

    /** [splitWanted]: the draw says the bot that takes the key is not the one that opens the door (it needs another bot to be free). */
    private class PaidDoor(val door: Int, val keyRoom: Int?, val keyT: Int, val openT: Int, val done: Boolean, val splitWanted: Boolean = false)

    private fun PlanMilestones.copy(bloodSeat: Int?) = PlanMilestones(bloodKeyT, bloodOpenT, watcherDoneT, maxorT, bloodSeat)

    private fun scheduleDoors(now: Int): DoorSchedule {
        // Plain doorways, the entrance door and the free fairy door.
        for (d in plan.doors) when (d.kind) {
            DoorKind.NORMAL -> pass[d.index] = 0
            DoorKind.ENTRANCE -> pass[d.index] = BotTuning.DOOR_PASSABLE_AFTER + 2
            DoorKind.FAIRY -> pass[d.index] = BotTuning.DOOR_PASSABLE_AFTER + 1
            else -> {}
        }
        for ((door, p) in st.passableAt) pass[door] = p

        val pathRooms = plan.path
        val n = pathRooms.size
        // Paid doors in path order (a wither door off the path, which a complete layout never has, goes last).
        val onPath = plan.pathDoors.filter { it.kind == DoorKind.WITHER }
        val paidDoors = onPath + plan.doors.filter { it.kind == DoorKind.WITHER && onPath.none { o -> o.index == it.index } }
        // Key rooms feeding them: path rooms after the entrance up to the one before the blood-key room, minus the Fairy room.
        val keyRooms = if (n >= 4) (1..n - 3).map { pathRooms[it] }.filter { it != fairy } else emptyList()

        val reachNow = { geo.reachTimes(entrance) { d -> pass[d] ?: FAR } }
        val botCount = inp.seats.count { !it.isHuman }
        val paid = ArrayList<PaidDoor>()
        var prevOpen = now
        // The tick the door before fell (planned or done), which the next door's gap is drawn from.
        var prevOpenT: Int? = null
        // The last paid door still to open (its opener walks on from there).
        var prevDoor: Int? = null
        var keysLeft = st.witherKeys
        for ((j, door) in paidDoors.withIndex()) {
            val keyRoom = keyRooms.getOrNull(j)
            val already = pass[door.index]
            if (already != null) {
                val openedAt = (already - BotTuning.DOOR_PASSABLE_AFTER).coerceAtLeast(0)
                paid += PaidDoor(door.index, keyRoom, now, openedAt, true); prevOpen = maxOf(prevOpen, already); prevOpenT = openedAt
                continue
            }
            val splitWanted = botCount >= 2 && pick.nextDouble() < BotTuning.KEY_SPLIT_CHANCE
            val u = pace.u()
            var keyUse = tables.keyToUse().sample(roomPace.u()).roundToInt().coerceAtLeast(1)
            // One bot that kills the key room's last mob and then opens the door needs the walk between the two before the door can fall.
            val keyUseMin = if (!splitWanted && keyRoom != null) walkTicks(geo.centre(keyRoom), keyRoom, door.index) + BotTuning.OPEN_SETTLE else 1
            keyUse = maxOf(keyUse, keyUseMin)
            val reach = reachNow()
            val keyReach = keyRoom?.let { reach[it].takeIf { r -> r < FAR } ?: now } ?: now
            // From the entrance room's exit door, where the countdown leaves the party (Countdown), not from the room's middle.
            val exit = plan.doors.firstOrNull { it.kind == DoorKind.ENTRANCE }?.let { geo.doorPt(it.index) } ?: geo.centre(entrance)
            val travel = keyRoom?.let { ticksAtMax(geo.length(geo.route(exit, entrance, it))) } ?: 0
            // The opener has to get to the door too: from the exit door for the first, from the door before (once it can be passed) for the rest.
            val doorSpot = geo.doorPt(door.index)
            val openerWalk = prevDoor?.let { pdoor -> prevOpen + BotTuning.DOOR_PASSABLE_AFTER + ticksAtMax(geo.length(expand(geo.route(geo.doorPt(pdoor), nearRoom(pdoor), nearRoom(door.index), doorSpot)))) }
                ?: ((reach[nearRoom(door.index)].takeIf { it < FAR } ?: now) + ticksAtMax(geo.length(expand(geo.route(exit, entrance, nearRoom(door.index), doorSpot)))))
            val lo = maxOf(now + 1, prevOpen + BotTuning.MIN_DOOR_GAP, keyReach + BotTuning.MIN_CLEAR_TICKS + keyUseMin + (if (j == 0) travel else 0), openerWalk + BotTuning.OPEN_SETTLE)
            var openT: Int
            var keyT: Int
            if (keysLeft > 0) {
                // The party already holds a key: this door goes as soon as the opener can get there.
                keysLeft--
                openT = lo.coerceAtLeast(now + keyUse); keyT = now
            } else {
                // The first door from its own table (with the party's pace); each later one a recorded gap after the door before it.
                val drawn = prevOpenT?.let { it + tables.doorGap(j + 1).sample(gaps.nextDouble()) } ?: (if (j == 0) tables.firstDoor() else tables.doorOpen(j + 1)).sample(u)
                openT = maxOf(drawn.roundToInt(), lo)
                // The first key room is a walk from the exit door away: its last mob cannot die before somebody gets there.
                keyT = (openT - keyUse).coerceAtLeast(keyReach + BotTuning.MIN_CLEAR_TICKS + (if (j == 0) travel else 0))
            }
            pass[door.index] = openT + BotTuning.DOOR_PASSABLE_AFTER
            prevOpen = openT
            prevDoor = door.index
            prevOpenT = openT
            paid += PaidDoor(door.index, keyRoom, keyT, openT, false, splitWanted)
        }

        // The blood key (the last path room before the blood room) and the blood door.
        val bloodDoor = plan.doors.firstOrNull { it.kind == DoorKind.BLOOD }
        val bloodKeyRoom = if (n >= 2) pathRooms[n - 2] else null
        val reach = reachNow()
        val lastPaid = paid.maxOfOrNull { it.openT } ?: now
        val keyFloor = maxOf(now + 1, lastPaid + BotTuning.OPENER_LEAD + 4, (bloodKeyRoom?.let { reach[it].takeIf { r -> r < FAR } } ?: 0) + BotTuning.MIN_CLEAR_TICKS)
        // After the last paid door by the recorded gap (it does not depend on how many doors there were); with no paid door, from T0.
        val keyDraw = if (paid.isNotEmpty()) lastPaid + tables.bloodKeyAfterLastDoor().sample(gaps.nextDouble()) else tables.bloodKey().sample(pace.u())
        val bloodKeyT = maxOf(keyDraw.roundToInt(), keyFloor)
        val bloodAlready = bloodDoor?.let { pass[it.index] }
        val bloodSplit = botCount >= 2 && pick.nextDouble() < BotTuning.KEY_SPLIT_CHANCE
        var doorAfterKey = tables.bloodDoorAfterKey().sample(roomPace.u()).roundToInt().coerceAtLeast(1)
        if (!bloodSplit && bloodDoor != null && bloodKeyRoom != null) doorAfterKey = maxOf(doorAfterKey, walkTicks(geo.centre(bloodKeyRoom), bloodKeyRoom, bloodDoor.index) + BotTuning.OPEN_SETTLE)
        val bloodOpenT = bloodAlready?.let { (it - BotTuning.DOOR_PASSABLE_AFTER).coerceAtLeast(0) } ?: (bloodKeyT + doorAfterKey)
        if (bloodDoor != null && bloodAlready == null) pass[bloodDoor.index] = bloodOpenT + BotTuning.DOOR_PASSABLE_AFTER
        // The camp and the line before Maxor: the pack's numbers, not a mechanism.
        val watcherDone = bloodOpenT + tables.campTicks().sample(roomPace.u()).roundToInt()
        val maxor = watcherDone + tables.provenToMaxor().sample(roomPace.u()).roundToInt()
        // Every key room's mobs die for its key at a planned tick: the room list reads them as the white time of those rooms.
        for (p in paid) if (!p.done && p.keyRoom != null) keyWhite[p.keyRoom] = p.keyT
        if (bloodAlready == null && bloodKeyRoom != null) keyWhite[bloodKeyRoom] = bloodKeyT
        return DoorSchedule(PlanMilestones(bloodKeyT, bloodOpenT, watcherDone, maxor, null), paid, bloodDoor?.index, bloodKeyRoom, bloodDoor != null && bloodAlready == null, bloodSplit)
    }

    private fun ticksAtMax(len: Double) = Math.ceil(len / BotTuning.MAX_TRAVEL_BPT).toInt()

    /** The room of a door's two sides that is nearer the path's start: the side its opener stands on. */
    private fun nearRoom(door: Int): Int {
        val d = plan.doors[door]
        val ia = plan.path.indexOf(d.roomA).let { if (it < 0) Int.MAX_VALUE else it }
        val ib = plan.path.indexOf(d.roomB).let { if (it < 0) Int.MAX_VALUE else it }
        return if (ia <= ib) d.roomA else d.roomB
    }

    /** Ticks to run from [from] (in [fromRoom]) to a door's gap at the planning maximum speed: the least a key-room-to-door walk can take. */
    private fun walkTicks(from: Pt, fromRoom: Int, door: Int): Int = ticksAtMax(geo.length(expand(geo.route(from, fromRoom, nearRoom(door), geo.doorPt(door)))))

    /** Where an opener stands when the door falls: [BotTuning.OPENER_STAND_BLOCKS] from the gap, into the room on its near side. */
    private fun standPoint(door: Int, near: Int): Pt {
        val spot = geo.doorPt(door)
        val centre = geo.centre(near)
        val len = spot.dist(centre)
        if (len < 1e-6) return spot
        val d = PlanMath.curve(BotTuning.OPENER_STAND_BLOCKS, pick.nextDouble())
        return spot.lerp(centre, minOf(d / len, 1.0))
    }

    /**
     * Door crew: one bot opens every door (58% in the recordings) or two share (the rest); who is drawn by the class table. The bot that
     * kills a key room's last mob and takes the key is another one in most games ([BotTuning.KEY_SPLIT_CHANCE]): the opener goes straight to
     * the door and the nearest free bot does the key room. With no other bot free, or none that can be there in time, the opener does both.
     */
    private fun assignDoorDuties(ms: DoorSchedule, bots: List<Seat>, builders: Map<Int, Builder>, doorPlans: MutableMap<Int, DoorPlan>, rooms: MutableMap<Int, RoomPlan>) {
        val open = ms.paid.filter { !it.done }
        val bloodPending = ms.bloodPending
        if (bots.isEmpty() || (open.isEmpty() && !bloodPending)) return
        fun draw(excl: Int?): Seat {
            val cand = bots.filter { it.index != excl }
            val w = cand.map { tables.doorOpenerShare(it.dungeonClass) + 0.02 }
            var x = pick.nextDouble() * w.sum()
            for ((i, c) in cand.withIndex()) { x -= w[i]; if (x <= 0) return c }
            return cand.last()
        }
        val hinted = st.openerHint?.let { h -> bots.firstOrNull { it.index == h } }
        val first = hinted ?: draw(null)
        // Two openers: the second takes the doors from a drawn index on.
        val total = open.size + if (bloodPending) 1 else 0
        val second = if (hinted == null && bots.size >= 2 && total >= 2 && pick.nextDouble() < BotTuning.SECOND_OPENER_CHANCE) draw(first.index) else null
        val handoff = if (second != null) pick.nextInt(1, total) else total
        val crewDoors = open
        fun seatOf(idx: Int) = if (idx < handoff) first else second ?: first
        val bySeat = LinkedHashMap<Int, MutableList<Pair<PaidDoor?, Int>>>()   // seat -> (paid door or null for the blood door, index)
        for ((i, p) in crewDoors.withIndex()) bySeat.getOrPut(seatOf(i).index) { ArrayList() } += p to i
        if (bloodPending) bySeat.getOrPut(seatOf(crewDoors.size).index) { ArrayList() } += null to crewDoors.size

        // The key rooms another bot kills: door index to the killer seat.
        val killerOf = HashMap<Int, Int>()
        val crewSeats = setOfNotNull(first.index, second?.index)
        val killers = bots.filter { it.index !in crewSeats && builders[it.index] != null }
        if (killers.isNotEmpty()) {
            val busyUntil = HashMap<Int, Int>(); val roomAt = HashMap<Int, Int>(); val lastStart = HashMap<Int, Int>()
            val wanted = crewDoors.filter { it.splitWanted && it.keyRoom != null }.map { Triple(it.door, it.keyRoom!!, it.keyT) } +
                (if (bloodPending && ms.bloodSplit && ms.bloodDoor != null && ms.bloodKeyRoom != null) listOf(Triple(ms.bloodDoor, ms.bloodKeyRoom, ms.milestones.bloodKeyT)) else emptyList())
            // A room that is already done, that the human has taken, or whose "mobs" are a puzzle stays with the opener's own key kill.
            val keyRoomOk = { room: Int -> room !in st.white && room !in st.green && room !in st.humanRooms && plan.rooms.firstOrNull { it.index == room }?.type != RoomType.PUZZLE }
            for ((door, keyRoom, keyT) in wanted.sortedBy { it.third }) {
                if (!keyRoomOk(keyRoom)) continue
                // The free bot that reaches the room soonest, if it gets there in time to kill the last mob when the key is due.
                val tries = killers.map { k ->
                    val b = builders.getValue(k.index)
                    val from = roomAt[k.index]?.let { geo.centre(it) } ?: b.pos
                    val fromRoom = roomAt[k.index] ?: b.room
                    val bpt = tables.moveBpt(k.dungeonClass) * inp.speedScale
                    // The walk as it will be planned: round the rooms' contents and held at doors not yet open.
                    val route = geo.route(from, fromRoom, keyRoom, geo.centre(keyRoom))
                    val t0 = maxOf(b.t, busyUntil[k.index] ?: 0)
                    // At the speed a walk with a deadline squeezes up to (see [walk]).
                    Triple(k, if (route.size < 2) t0 else walkEnd(expand(route), t0, maxOf(bpt, BotTuning.MAX_TRAVEL_BPT)), pick.nextDouble())
                }
                val best = tries.minWithOrNull(compareBy({ it.second }, { it.third })) ?: continue
                if (best.second > keyT - KEY_CLEAR_WINDOW + SPLIT_SLACK) continue
                killerOf[door] = best.first.index
                busyUntil[best.first.index] = keyT; roomAt[best.first.index] = keyRoom
                val b = builders.getValue(best.first.index)
                val pr = plan.rooms.first { it.index == keyRoom }
                val whiteEst = ((tables.roomToWhite(pr)?.median ?: (tables.roomDur(pr, best.first.dungeonClass).median / 5.0)) * inp.workScale).toInt()
                // Keys come in key order; a killer's later key never starts before its earlier one (a slow room's early start ran it first).
                val startBy = maxOf(b.t, keyT - whiteEst - (best.second - b.t) - 20, (lastStart[best.first.index] ?: Int.MIN_VALUE) + 1)
                lastStart[best.first.index] = startBy
                killerRooms += keyRoom
                b.duties += Duty(startBy) { bb -> keyVisit(bb, pr, keyT, rooms) }
            }
        }

        val split: Map<Int, Int> = killerOf
        for ((seatIdx, list) in bySeat) {
            val b = builders[seatIdx] ?: continue
            val firstDoor = list.first().first
            val firstDoorIdx = firstDoor?.door ?: ms.bloodDoor
            val firstOpenT = firstDoor?.openT ?: ms.milestones.bloodOpenT
            val firstKeyT = firstDoor?.keyT ?: ms.milestones.bloodKeyT
            val firstRoom = firstDoor?.keyRoom ?: ms.bloodKeyRoom ?: entrance
            val startBy = when {
                list.first().second == 0 -> b.t
                firstDoorIdx != null && firstDoorIdx in split -> maxOf(b.t, firstOpenT - geoTicks(b, geo.doorPt(firstDoorIdx), nearRoom(firstDoorIdx)) - 20)
                else -> maxOf(b.t, firstKeyT - geoTicks(b, geo.centre(firstRoom), b.room) - 20)
            }
            b.duties += Duty(startBy) { bb -> crewDuty(bb, list.map { it.first }, split, ms, doorPlans, rooms) }
        }
    }

    /** Time for a seat at its position to walk to [to] at its own speed, round the rooms' contents and held at closed doors, or 0. */
    private fun geoTicks(b: Builder, to: Pt, toRoom: Int): Int {
        val route = geo.route(b.pos, b.room, toRoom, to)
        if (route.size < 2) return 0
        return walkEnd(expand(route), b.t, (tables.moveBpt(b.seat.dungeonClass) * inp.speedScale).coerceAtLeast(0.1)) - b.t
    }

    /** A bot's visit to a key room: a normal room visit, timed so the last mob dies when the key is due. */
    private fun keyVisit(b: Builder, room: PlacedRoom, keyT: Int, rooms: MutableMap<Int, RoomPlan>) {
        val reachT = geo.reachTimes(entrance) { d -> pass[d] ?: FAR }.getOrElse(room.index) { FAR }.takeIf { it < FAR } ?: 0
        assign(b, Task(room, RoomWork.FULL, reachT), rooms, pin = keyT)
    }

    /** To the key room, in time to kill its last mob when the key is due, and wait there for it. */
    private fun keyKill(b: Builder, keyRoom: Int, keyT: Int, bpt: Double, rooms: MutableMap<Int, RoomPlan>) {
        if (b.room != keyRoom || b.pos.dist(geo.centre(keyRoom)) > 4.0) walk(b, geo.route(b.pos, b.room, keyRoom, geo.spread(geo.centre(keyRoom), b.seat.index, 5)), bpt, keyT - 2)
        b.room = keyRoom
        val from = maxOf(b.t, keyT - KEY_CLEAR_WINDOW)
        b.stand(from)
        b.acts += ClearMobs(b.seat.index, from, maxOf(from, keyT), keyRoom)
        b.stand(maxOf(b.t, keyT))
        recordKeyRoom(rooms, keyRoom, b.seat, from, keyT)
    }

    /**
     * The doors one seat opens, in order: to the key room and kill the last mob there (unless another bot does, [split]), to the door, open
     * it. A null door is the blood door. The seat stands beside the door when it falls; a door it reaches late falls late, so the plan never
     * has a door open under a bot that is not there.
     */
    private fun crewDuty(b: Builder, doors: List<PaidDoor?>, split: Map<Int, Int>, ms: DoorSchedule, doorPlans: MutableMap<Int, DoorPlan>, rooms: MutableMap<Int, RoomPlan>) {
        val bpt = tables.moveBpt(b.seat.dungeonClass) * inp.speedScale
        for (pd in doors) {
            val keyRoom = pd?.keyRoom ?: ms.bloodKeyRoom
            val keyT = pd?.keyT ?: ms.milestones.bloodKeyT
            val doorIdx = pd?.door ?: ms.bloodDoor
            val openT = pd?.openT ?: ms.milestones.bloodOpenT
            if (keyRoom != null && (doorIdx == null || doorIdx !in split)) keyKill(b, keyRoom, keyT, bpt, rooms)
            if (doorIdx != null) {
                val near = nearRoom(doorIdx)
                val stand = standPoint(doorIdx, near)
                if (b.pos.dist(stand) > 1.0) walk(b, geo.route(b.pos, b.room, near, stand), bpt, openT - BotTuning.OPENER_LEAD)
                b.room = near
                // Late for its door: the door waits for the walker.
                val openAt = maxOf(openT, b.t + BotTuning.OPEN_SETTLE)
                if (openAt > openT) pass[doorIdx] = openAt + BotTuning.DOOR_PASSABLE_AFTER
                b.stand(maxOf(b.t, openAt))
                b.acts += OpenDoor(b.seat.index, openAt, doorIdx, geo.doorPt(doorIdx))
                doorPlans[doorIdx] = DoorPlan(doorIdx, b.seat.index, keyT, openAt, split[doorIdx] ?: b.seat.index)
                // Through the door and on, once it can be passed.
                b.stand(maxOf(b.t, openAt + BotTuning.DOOR_PASSABLE_AFTER))
            }
        }
    }

    private fun recordKeyRoom(rooms: MutableMap<Int, RoomPlan>, room: Int, seat: Seat, from: Int, whiteT: Int) {
        keyWhite[room] = whiteT
        val r = plan.rooms.first { it.index == room }
        rooms.putIfAbsent(room, RoomPlan(room, seat.index, if (r.secretsMax > 0) RoomWork.WHITE_ONLY else RoomWork.FULL, from, whiteT, if (r.secretsMax > 0) null else whiteT, false))
    }

    /** Rooms whose mobs the door crew kills for a key, with the tick the last one dies. The room's secrets are done later, by the room list. */
    private val keyWhite = HashMap<Int, Int>()

    /** The seat the strategy sends to the blood room: it arrives (by the strategy's tick when it gave one) and stays till the camp's end. */
    private fun campDuty(b: Builder, ms: PlanMilestones, arriveBy: Int?) {
        val room = bloodRoom ?: return
        val bpt = tables.moveBpt(b.seat.dungeonClass) * inp.speedScale
        val dest = geo.spread(geo.centre(room), b.seat.index, 5)
        walk(b, geo.route(b.pos, b.room, room, dest), bpt, arriveBy)
        b.room = room
        b.stand(maxOf(b.t, ms.watcherDoneT))
    }

    /** A room the seat was already in when the plan was rebuilt: the acts still to do and the legs that stand there until it is done. */
    private fun commitRoom(b: Builder, c: Commit, rooms: MutableMap<Int, RoomPlan>) {
        val now = st.now
        val soft = b.seat.isHuman
        when (c.work) {
            RoomWork.FULL, RoomWork.WHITE_ONLY -> if (c.whiteT != null && c.whiteT > now) b.acts += ClearMobs(c.seat, now, c.whiteT, c.room)
            else -> {}
        }
        if (c.work != RoomWork.WHITE_ONLY && c.work != RoomWork.PUZZLE && c.greenT > now) b.acts += FindSecrets(c.seat, maxOf(now, c.whiteT ?: now), c.greenT, c.room)
        if (c.work == RoomWork.PUZZLE && c.greenT > now) b.acts += SolvePuzzle(c.seat, now, c.greenT, c.room)
        b.stand(maxOf(b.t, c.greenT))
        b.room = c.room
        rooms[c.room] = RoomPlan(c.room, c.seat, c.work, c.arriveT, c.whiteT, c.greenT, soft)
    }

    // ===================================================================================================================
    // the rooms

    private class Task(val room: PlacedRoom, val work: RoomWork, val avail: Int)

    /** The room list being worked through, the random it uses and the clear's expected end: shared with the duties that visit a room (see [assign]). */
    private val restTasks = ArrayList<Task>()
    private var roomRand: Random = Random(0)
    private var roomDeadline = Int.MAX_VALUE
    /** Key rooms another bot visits as a normal room with its mobs dying at the key tick: not in the room list. */
    private val killerRooms = HashSet<Int>()

    private fun scheduleRooms(builders: Map<Int, Builder>, rooms: MutableMap<Int, RoomPlan>, ms: DoorSchedule) {
        val reach = geo.reachTimes(entrance) { d -> pass[d] ?: FAR }
        val pool = ArrayList<Task>()
        for (r in plan.rooms) {
            if (r.type == RoomType.ENTRANCE || r.type == RoomType.FAIRY || r.type == RoomType.BLOOD) continue
            if (r.index in st.green || r.index in st.humanRooms || rooms.containsKey(r.index) || r.index in killerRooms) continue
            val reachT = reach.getOrElse(r.index) { FAR }
            if (reachT >= FAR) continue
            val kw = keyWhite[r.index]
            val work = when {
                r.index in st.white || kw != null -> if (r.secretsMax > 0) RoomWork.GREEN_ONLY else null
                r.type == RoomType.PUZZLE -> RoomWork.PUZZLE
                else -> RoomWork.FULL
            } ?: continue
            pool += Task(r, work, maxOf(reachT, kw ?: 0))
        }
        // A key room with no secrets is complete once the crew has whitened it, so it is not in the pool.
        val rest = restTasks
        rest.clear(); rest += pool.sortedBy { it.room.index }
        roomRand = Random(taskSeed)
        roomDeadline = ms.milestones.maxorT
        var guard = 0
        while (rest.isNotEmpty() && guard++ < 10_000) {
            val b = builders.values.minByOrNull { it.t * 10 + it.seat.index } ?: break
            // A seat with duty due runs it first.
            val duty = b.duty
            val bpt = tables.moveBpt(b.seat.dungeonClass) * inp.speedScale
            var best: Task? = null
            var bestCost = Double.MAX_VALUE
            // Mobs first: a room that still needs its mobs killed (or its puzzle solved) that can be started about now beats one that only has secrets left.
            val promptFresh = rest.any { it.work != RoomWork.GREEN_ONLY && it.avail <= b.t + PROMPT_SLACK + 40 }
            for (t in rest) {
                if (promptFresh && t.work == RoomWork.GREEN_ONLY) continue
                val route = geo.route(b.pos, b.room, t.room.index)
                val travel = Math.ceil(geo.length(route) / bpt.coerceAtLeast(0.1)).toInt()
                val start = maxOf(b.t + travel, t.avail)
                var cost = start.toDouble()
                if (t.work == RoomWork.PUZZLE) cost += (1.0 - tables.puzzleAffinity(b.seat.dungeonClass)) * BotTuning.PUZZLE_AFFINITY_PENALTY
                if (duty != null) {
                    // It must still make the duty after this room: its time here (a typical duration) plus the walk on.
                    val est = start + (tables.roomDur(t.room, b.seat.dungeonClass).median * inp.workScale).toInt().coerceAtMost(900)
                    if (est > duty.startBy - 10) cost += OVER_DUTY_PENALTY
                }
                if (cost < bestCost - 1e-9) { bestCost = cost; best = t }
            }
            if (duty != null && (best == null || bestCost >= OVER_DUTY_PENALTY || b.t >= duty.startBy - 10)) {
                b.duties.remove(duty)
                duty.run(b)
                continue
            }
            val task = best ?: break
            rest.remove(task)
            assign(b, task, rooms)
        }
        // Duties of seats that never took a room still run.
        for (b in builders.values) while (true) { val d = b.duty ?: break; b.duties.remove(d); d.run(b) }
    }

    /**
     * Gives [b] the room of [task] as its next piece of work. With [pin] set (a key room another bot kills for the key) the room's mobs die at
     * that tick: the bot is there in time for them, and a head start only shortens the clearing. The visit is a normal one otherwise (the
     * secrets follow), so the bot is not idle while it waits for the key.
     */
    private fun assign(b: Builder, task: Task, rooms: MutableMap<Int, RoomPlan>, pin: Int? = null) {
        val rr = roomRand
        val rest = restTasks
        val deadline = roomDeadline
        val room = task.room
        val cls = b.seat.dungeonClass
        val bpt = tables.moveBpt(cls) * inp.speedScale
        val centre = geo.centre(room.index)
        val soft = b.seat.isHuman
        // Durations (they do not depend on when the seat gets there).
        val u = roomPace.u()
        val dur = tables.roomDur(room, cls).sample(u) * inp.workScale
        val gFull = dur.roundToInt().coerceAtLeast(BotTuning.MIN_ROOM_DWELL)
        val hasSecrets = room.secretsMax > 0
        var whiteOff: Int
        val greenOff: Int
        when (task.work) {
            RoomWork.GREEN_ONLY -> {
                val g = tables.roomToGreen(room)?.sample(roomPace.u())?.times(inp.workScale) ?: (dur * 0.7)
                whiteOff = 0; greenOff = g.roundToInt().coerceAtLeast(BotTuning.MIN_GREEN_TASK)
            }
            RoomWork.PUZZLE -> { whiteOff = gFull; greenOff = gFull }
            else -> {
                val w = if (hasSecrets) (tables.roomToWhite(room)?.sample(roomPace.u())?.times(inp.workScale)?.roundToInt() ?: (gFull / 5)) else gFull
                whiteOff = w.coerceIn(BotTuning.MIN_CLEAR_TICKS, gFull)
                greenOff = if (hasSecrets) gFull.coerceAtLeast(whiteOff + 1) else whiteOff
            }
        }
        // The arrival: the walk through the door graph, held at closed doors until they can be passed.
        val target = pin?.let { it - whiteOff }
        // A pinned visit already short of its full clear time still hurries to make the shortest clear before the key is due.
        val walkBy = if (pin != null && target != null && target <= b.t) pin - BotTuning.MIN_CLEAR_TICKS else target
        if (!soft) walk(b, geo.route(b.pos, b.room, room.index, centre), bpt, walkBy)
        else {
            val len = geo.length(geo.route(b.pos, b.room, room.index, centre))
            b.t += Math.ceil(len / bpt.coerceAtLeast(0.1)).toInt(); b.pos = centre
        }
        b.room = room.index
        val arrive = maxOf(b.t, task.avail, target ?: Int.MIN_VALUE)
        if (!soft) b.stand(arrive) else b.t = arrive
        if (pin != null && task.work != RoomWork.GREEN_ONLY) whiteOff = (pin - arrive).coerceIn(BotTuning.MIN_CLEAR_TICKS, maxOf(whiteOff, BotTuning.MIN_CLEAR_TICKS))
        // Short of time (the visit's secrets would run past the clear's expected end), the seat clears the mobs and moves on; the secrets wait in the list.
        var work = task.work
        var greenOff2 = greenOff
        val freshWaiting = rest.any { it.work != RoomWork.GREEN_ONLY && it.avail <= arrive + whiteOff + PROMPT_SLACK }
        // So too when the seat has a duty (a key, a door) due before the secrets would be done.
        val dutySoon = !soft && b.duty?.let { arrive + greenOff > it.startBy - 10 } == true
        if (work == RoomWork.FULL && hasSecrets && whiteOff < greenOff && (arrive + greenOff > deadline || freshWaiting || dutySoon)) {
            work = RoomWork.WHITE_ONLY
            greenOff2 = whiteOff
            keyWhite[room.index] = arrive + whiteOff
            rest += Task(room, RoomWork.GREEN_ONLY, arrive + whiteOff)
        }
        val whiteT = if (work == RoomWork.GREEN_ONLY) keyWhite[room.index] ?: (if (room.index in st.white) st.now else null) else arrive + whiteOff
        val greenT = arrive + greenOff2
        if (!soft) {
            val spots = if (work == RoomWork.WHITE_ONLY) emptyList() else RoomRoutes.secretSpots(room, room.secretsMax)
            val walked = if (work == RoomWork.WHITE_ONLY) null else inp.traversals?.legs(room, b.pos, arrive, greenT, bpt, rr)
            val (legs, end) = walked ?: RoomRoutes.legs(arrive, greenT, b.pos, spots.map { it.pt }, centre, bpt, rr, inp.areas?.of(room) ?: FreeArea.of(room))
            b.legs += legs; b.pos = end
            when (work) {
                RoomWork.FULL -> {
                    b.acts += ClearMobs(b.seat.index, arrive, arrive + whiteOff, room.index)
                    if (hasSecrets) b.acts += FindSecrets(b.seat.index, arrive + whiteOff, greenT, room.index)
                }
                RoomWork.GREEN_ONLY -> b.acts += FindSecrets(b.seat.index, arrive, greenT, room.index)
                RoomWork.PUZZLE -> b.acts += SolvePuzzle(b.seat.index, arrive, greenT, room.index)
                RoomWork.WHITE_ONLY -> b.acts += ClearMobs(b.seat.index, arrive, arrive + whiteOff, room.index)
            }
        } else b.pos = centre
        b.t = greenT
        rooms[room.index] = RoomPlan(room.index, b.seat.index, work, arrive, whiteT, if (work == RoomWork.WHITE_ONLY) null else greenT, soft)
    }

    // ===================================================================================================================
    // walking

    /**
     * Appends the legs of a walk along [route] to [b], at [bpt], standing at each closed door until it can be passed. With a [deadline]
     * that the natural walk misses, the legs speed up to make it (up to [BotTuning.MAX_TRAVEL_BPT]); beyond that speed they hop.
     */
    /** Inside a room the walk goes round what is in the room (the floor is read from the room's blocks), not through it. */
    private fun expand(route0: List<RoutePoint>): List<RoutePoint> =
        inp.areas?.let { areas -> RouteExpander.expand(route0) { p -> geo.roomAt(p)?.let { areas.of(it) } } } ?: route0

    /** The tick a walk of [route] started at [t0] ends: legs at [speed], held at each closed door until it is passed. Legs go to [legs] when given. */
    private fun walkEnd(route: List<RoutePoint>, t0: Int, speed: Double, legs: MutableList<Leg>? = null): Int {
        var t = t0
        for (i in 1 until route.size) {
            val a = route[i - 1].pt; val c = route[i].pt
            val len = a.dist(c)
            val ticks = if (len <= 0.0) 0 else Math.ceil(len / speed).toInt()
            if (legs != null && (len > 0.0 || ticks > 0)) legs += Leg(t, t + ticks, a, c, hop = ticks > 0 && len / ticks > BotTuning.HOP_ABOVE_BPT)
            t += ticks
            val gate = route[i].gate
            if (gate != null) {
                val p = pass[gate] ?: FAR
                if (p in (t + 1) until FAR) { legs?.add(Leg(t, p, c, c)); t = p }
            }
        }
        return t
    }

    private fun walk(b: Builder, route0: List<RoutePoint>, bpt: Double, deadline: Int?) {
        if (route0.size < 2) return
        val route = expand(route0)
        fun simulate(speed: Double, commit: Boolean): Int = walkEnd(route, b.t, speed, if (commit) b.legs else null)
        var speed = bpt.coerceAtLeast(0.1)
        // Already late: it hurries all the way.
        if (deadline != null && deadline <= b.t) speed = maxOf(speed, BotTuning.MAX_TRAVEL_BPT)
        else if (deadline != null) {
            val natural = simulate(speed, false)
            if (natural > deadline) {
                // Squeeze the walk to the slowest speed that makes the deadline, up to the planning maximum (a hold at a closed door does not squeeze).
                var lo = speed; var hi = maxOf(speed, BotTuning.MAX_TRAVEL_BPT)
                if (simulate(hi, false) > deadline) speed = hi
                else { repeat(12) { val mid = (lo + hi) / 2; if (simulate(mid, false) <= deadline) hi = mid else lo = mid }; speed = hi }
            }
        }
        b.t = simulate(speed, true)
        b.pos = route.last().pt
    }

    companion object {
        /** A door that is never passed (not yet planned), as a tick. */
        const val FAR = Int.MAX_VALUE / 2

        /** How long before the key is due the crew bot starts killing in the key room. UNMEASURED. */
        const val KEY_CLEAR_WINDOW = 12

        /** Added to the cost of a room that would make a seat late for its duty: a duty beats a room. */
        const val OVER_DUTY_PENALTY = 1.0e7

        /** A room counts as startable "about now" when it opens within this many ticks of a seat getting there. UNMEASURED. */
        const val PROMPT_SLACK = 30

        /** A bot may be chosen to kill a key room when it can get there this many ticks later than the kill window opens. UNMEASURED. */
        const val SPLIT_SLACK = 10
    }
}
