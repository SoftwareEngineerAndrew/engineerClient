package com.engineerclient.p3sim.clear.bots

import kotlin.random.Random

/** A teammate's leap: at [t] seat [seat] teleports to seat [to], who opened wither door [door] a moment before. */
class Leap(val t: Int, val seat: Int, val to: Int, val door: Int)

/** A wither door a person opened at [t], standing at [at]: the bots may leap to them as to a bot opener. */
class PersonDoor(val door: Int, val seat: Int, val t: Int, val at: Pt)

/** What the leap pass needs from the plan's geometry. */
interface LeapWorld {
    /** Whether the walk from [from] to [to] goes through door [door]. */
    fun through(from: Pt, to: Pt, door: Int): Boolean

    /** The legs of a walk from [from] to [to] starting at [t0] (round what is in the rooms, held at closed doors). */
    fun walk(from: Pt, to: Pt, t0: Int): List<Leg>
}

/**
 * Teammates leaping to the door opener, the way real parties move up: a moment after a bot opens a wither door (or as it finishes the room it is
 * in, if that is soon), another bot far from it whose next walk goes through that door leaps to the opener, posts "Leaped to <name>!" and walks on
 * from there to where its plan wanted it, waiting there if early. Every act keeps its time (timing first; the leap only changes how it gets there).
 *
 * Measured (ADR, 324 five-player F7 clears, teammates other than the recorder): 3.4 "Leaped to" lines a run; after a wither door 0.5 of the three
 * other teammates leap to its opener (0.4 to 0.6 by door, the last door no different), p10/p50/p90 6/17/45 ticks after the door. From the map
 * markers: the leaper jumps p10/p50/p90 21/49/87 blocks, was 13/37/73 blocks from the opener when the door opened, and was moving (258 of 271),
 * so busy clearing, not waiting at the door. Spellings, fixed
 * per player: "Leaped to X!" 80%, "Leaping to X" 11%, "Leaped to X" 9%. About 1.2 more a run go to someone during the blood camp (not modelled).
 * UNMEASURED: who leaps. Here every bot that can (far, its next walk through the door) does; on the real layouts that is 0.15 a door, not 0.5:
 * the bots' plans hold more of them at the closed door near the opener than people stand there, and send the rest elsewhere.
 */
object Leaps {
    /** Chance a bot that could leap does (all: the bots can less often than people, see above). */
    const val TRY = 1.0
    /** A bot nearer than this to the opener walks (the recorded jumps are 21 blocks or more nine times in ten). */
    const val MIN_BLOCKS = 16.0
    /** The latest a leap comes after its door (ticks; the recorded p90 is 45). */
    const val LATEST = 90
    /** A stand this long ends a walk (a camp, a gathering point, a hold at a closed door). */
    const val STAND_TICKS = 20
    /** Blocks from the opener the leaper lands. */
    const val LAND_BESIDE = 1.2
    /** Measured share of the three spellings. */
    val SPELLINGS = listOf("Leaped to %s!" to 0.80, "Leaping to %s" to 0.11, "Leaped to %s" to 0.09)

    /** Ticks from the door to the leap: the measured spread (p10 6, p50 17, p90 45), drawn piecewise. */
    fun gap(u: Double): Int = Math.round(PlanMath.curve(listOf(0.0 to 2.0, 0.1 to 6.0, 0.5 to 17.0, 0.9 to 45.0, 1.0 to 90.0), u)).toInt()

    /** The leap line a teammate's mod posts, in that teammate's spelling ([u] picks it once per teammate). */
    fun line(name: String, u: Double): String {
        var acc = 0.0
        for ((f, share) in SPELLINGS) { acc += share; if (u < acc) return f.format(name) }
        return SPELLINGS.first().first.format(name)
    }

    /**
     * The leaps of a plan and the tracks with them in. [doors] are the plan's door schedule (wither doors only are leapt after; [blood] names the
     * blood door to leave out), [humans] the seats that are people (never moved), [people] the doors people have just opened (leapt to where they
     * stood, since what they do next is not known).
     */
    fun add(tracks: List<SeatTrack>, doors: Collection<DoorPlan>, blood: Int?, humans: Set<Int>, world: LeapWorld, rnd: Random, people: List<PersonDoor> = emptyList()): Pair<List<SeatTrack>, List<Leap>> {
        val byseat = tracks.associateBy { it.seat }.toMutableMap()
        val leaps = ArrayList<Leap>()
        class Opened(val door: Int, val seat: Int, val openT: Int, val where: (Int) -> Pt?)
        val wither = (doors.filter { it.door != blood && it.seat !in humans }.map { d -> Opened(d.door, d.seat, d.openT) { t -> byseat[d.seat]?.positionAt(t) } } +
            people.filter { it.door != blood }.map { p -> Opened(p.door, p.seat, p.t) { p.at } }).sortedBy { it.openT }
        for ((i, d) in wither.withIndex()) {
            // Leaps of one door are over before the next opens (the opener may leap after it, which would move the spot).
            val nextOpen = wither.getOrNull(i + 1)?.openT ?: Int.MAX_VALUE
            val at = d.openT + gap(rnd.nextDouble())
            val to = d.where(at) ?: continue
            for (s in byseat.values.sortedBy { it.seat }.toList()) {
                if (s.seat == d.seat || s.seat in humans) continue
                if (rnd.nextDouble() >= TRY) continue
                // A bot in the middle of a room leaps as it finishes there, if that is soon enough.
                var from = at
                while (true) { val busy = s.acts.firstOrNull { it.t <= from && from <= it.until } ?: break; from = busy.until + 1 }
                if (from - d.openT > LATEST || from >= nextOpen) continue
                val tr = leapOf(s, from, if (from == at) to else d.where(from) ?: continue, d.door, world) ?: continue
                byseat[s.seat] = tr
                leaps += Leap(from, s.seat, d.seat, d.door)
            }
        }
        return tracks.map { byseat.getValue(it.seat) } to leaps.sortedBy { it.t }
    }

    /** [s] leaping at [at] to beside [to] and walking on to where its plan next needs it, or null when it would not leap (busy, near, not going that way, or late). */
    internal fun leapOf(s: SeatTrack, at: Int, to: Pt, door: Int, world: LeapWorld): SeatTrack? {
        if (s.acts.any { it.t <= at && at <= it.until }) return null
        val here = s.positionAt(at)
        if (here.dist(to) < MIN_BLOCKS) return null
        // Where the walk it is on (or about to start) ends: its next act, or the next long stand (a camp, a gathering point).
        val still = s.legs.firstOrNull { it.t0 > at && it.a == it.b && it.t1 - it.t0 >= STAND_TICKS }?.t0
        val due = minOf(s.acts.filter { it.t > at }.minOfOrNull { it.t } ?: s.endT, still ?: s.endT)
        if (due <= at) return null
        val dest = s.positionAt(due)
        if (!world.through(here, dest, door)) return null
        val gap = to.dist(dest)
        val land = if (gap < 0.01) to else to.lerp(dest, minOf(1.0, LAND_BESIDE / gap))
        val walk = world.walk(land, dest, at)
        if (walk.isEmpty() && land.dist(dest) > 1.0) return null   // no way to walk it
        val end = walk.lastOrNull()?.t1 ?: at
        if (end > due) return null
        val legs = ArrayList<Leg>()
        for (l in s.legs) {
            if (l.t1 <= at) legs += l
            else if (l.t0 < at) legs += Leg(l.t0, at, l.a, l.at(at), l.hop)
        }
        if (walk.isEmpty()) legs += Leg(at, at, land, land) else legs += walk
        if (due > end) legs += Leg(end, due, dest, dest)
        for (l in s.legs) {
            if (l.t0 >= due) legs += l
            else if (l.t1 > due) legs += Leg(due, l.t1, l.at(due), l.b, l.hop)
        }
        return SeatTrack(s.seat, legs, s.acts)
    }
}

/**
 * Teammates leaping into the blood camp: after the blood door, a teammate done with its rooms leaps to the one camping blood rather than
 * walking there, posts its leap line and stands beside it till the camp ends.
 *
 * Measured (ADR, 245 F7 clears, teammates other than the recorder, "Leap(ed|ing) to X" between the blood door and the boss): 1.69 a run;
 * p10/p50/p90 107/901/1398 ticks after the blood door, or 1000/287 ticks before the Watcher's "proven" line to 55 after (83% before it); to the blood door's opener 24%, to the recorder
 * 14%, to someone else 62%. UNMEASURED: which teammate (here any bot far from the camper by [CAMP_TRY], at the drawn moment or as its last room ends); those
 * after the "proven" line (not done: here every leap is before it).
 */
object CampLeaps {
    /** Chance a bot that could leap into the camp does: all (with three bots out of the camp that gives about 0.8 a run on the real layouts, short of the recorded 1.69, where people also leap twice and to others than the camper). */
    const val CAMP_TRY = 1.0
    /** Leaps this soon after the blood door are left out (the camper has only just walked in). */
    const val AFTER_DOOR = 20
    /** The door id a camp leap carries ([Leap.door]): no door. */
    const val NO_DOOR = -1

    /** Ticks after the blood door a camp leap comes: the measured spread (p10 107, p50 901, p90 1398), drawn piecewise. */
    fun afterDoor(u: Double): Int = Math.round(PlanMath.curve(listOf(0.0 to 40.0, 0.1 to 107.0, 0.5 to 901.0, 0.9 to 1398.0, 1.0 to 1700.0), u)).toInt()

    /**
     * The camp leaps of a plan and the tracks with them in: to [camper] (a bot seat camping the blood room), between [bloodOpenT] and
     * [provenT], none before [now]. [humans] are never moved. Each seat's draws come from [seed] (the dungeon's), not the plan's random, so a
     * re-plan keeps the moment: the plans are redrawn often and a leap redrawn each time would never come.
     */
    fun add(tracks: List<SeatTrack>, camper: Int?, bloodOpenT: Int, provenT: Int, now: Int, humans: Set<Int>, seed: Long): Pair<List<SeatTrack>, List<Leap>> {
        val camp = tracks.firstOrNull { it.seat == camper } ?: return tracks to emptyList()
        val leaps = ArrayList<Leap>()
        val out = tracks.map { s ->
            if (s.seat == camper || s.seat in humans) return@map s
            val rnd = Random(seed xor (0x5EEDL + s.seat) * -0x61c8864680b583ebL)
            if (rnd.nextDouble() >= CAMP_TRY) return@map s
            // At the drawn moment, or as its work ends if it is still busy then: a teammate still clearing does not leave it.
            val workDone = (s.acts.maxOfOrNull { it.until } ?: Int.MIN_VALUE) + 1
            val at = maxOf(bloodOpenT + afterDoor(rnd.nextDouble()), workDone, bloodOpenT + AFTER_DOOR)
            if (at >= provenT || at <= now) return@map s
            val to = camp.positionAt(at)
            if (s.positionAt(at).dist(to) < Leaps.MIN_BLOCKS) return@map s
            val here = s.positionAt(at)
            val gap = here.dist(to)
            val land = to.lerp(here, minOf(1.0, Leaps.LAND_BESIDE / gap))
            val legs = ArrayList<Leg>()
            for (l in s.legs) {
                if (l.t1 <= at) legs += l
                else if (l.t0 < at) legs += Leg(l.t0, at, l.a, l.at(at), l.hop)
            }
            legs += Leg(at, maxOf(at, s.endT, camp.endT), land, land)
            leaps += Leap(at, s.seat, camper!!, NO_DOOR)
            SeatTrack(s.seat, legs, s.acts)
        }
        return out to leaps
    }
}
