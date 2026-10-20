package com.engineerclient.p3sim.clear.blood.camp

import kotlin.math.floor
import kotlin.math.sqrt

/** A position in world coordinates. Every rule of the camp uses distances, so the room's own frame is never needed. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun length() = sqrt(this dot this)
    fun dist(o: Vec3) = (this - o).length()
}

/**
 * The Watcher's blood camp on the main server (Floor 7), as measured on recorded fights. Every tick here is a server tick counted
 * from the door line ("The BLOOD DOOR has been opened!"): "door+420" is 420 ticks after it. His first line comes 3 ticks after the
 * door.
 *
 * The camp is one server task that runs every 10 ticks from the door click (the loop ticks door+10m):
 *  - Kill counter: a blood mob that dies on tick d is counted on the first loop tick after d (a death on a loop tick waits a whole
 *    loop). Each counted kill takes 200 off the Watcher's 3000 health; the fifteenth count is his defeat and the pass line
 *    ("You have proven yourself.") prints on that loop tick, or up to 20 ticks later when his chat queue is busy.
 *  - Opening: from door+90, every 30 ticks while he is idle, he flies at 0.70 blocks a tick to a wall head and throws it. The four
 *    initial mobs (a giant, a revived boss and two standard mobs) land at about door+200, 241, 278 and 332. Then he flies home at
 *    0.50.
 *  - Later legs (11 heads, 12 when the Wandering Soul comes too): from door+420, only on a loop tick where he is idle (his last
 *    moving tick is before it). A mob is alive on a decision tick D when it has spawned and its death tick is D or later. With
 *    nobody alive he leaves FAST (0.6125 blocks a tick) on the door+40k ticks; with anybody alive he leaves SLOW (0.4375) on the
 *    door+60k ticks.
 *  - Every flight: face the target (the wall station plus one block up), from the next tick move v a tick in a straight line, stop
 *    when the squared distance to the target is below 1.5. He throws the head 10 ticks after the first tick at rest; a later head
 *    flies 40 ticks (the mob is added at throw+39), an initial one 80 (the mob at throw+79).
 *  - After the last later throw he flies home (0.50) on the next idle door+40k tick, whatever is alive.
 *  - The first later departure: with an initial mob alive on door+420 he leaves on door+420 SLOW; with all four dead he waits for
 *    door+440 (door+480 when a line of his is being said around it).
 */
object CampMech {
    const val LOOP = 10
    const val FAST_PERIOD = 40
    const val SLOW_PERIOD = 60

    /** The first later decision tick. */
    const val FIRST_LATER = 420
    const val OPEN_FIRST = 90
    const val OPEN_PERIOD = 30

    /** Blocks a tick: the opening hops, the flight home, and the later legs (home 0.50 times 7/8 slow, opening 0.70 times 7/8 fast). */
    const val V_OPEN = 0.70
    const val V_HOME = 0.50
    const val V_FAST = 0.70 * 0.875
    const val V_SLOW = 0.50 * 0.875

    /** A flight stops when the squared distance to its target is below this. */
    const val ARRIVE_R2 = 1.5

    /** Ticks from coming to rest at a head to throwing it. */
    const val THROW_DELAY = 10

    /** Ticks a thrown head flies: the four initial heads 80, the later ones 40. */
    const val FLIGHT_INITIAL = 80
    const val FLIGHT_LATER = 40

    /**
     * A mob is added this many ticks after its throw, a tick before its head lands: 39 for a later head, 79 for an initial one (139 DR runs:
     * spawn minus first move 37-40 later, 77-80 initial, means 38.8 and 78.9; the head goes 0 or 1 tick after the spawn, both kinds).
     */
    const val LATER_SPAWN = 39
    const val INITIAL_SPAWN = 79

    /** Ticks he must have been at home before a SLOW first later departure. */
    const val HOME_MIN = 40

    /** The FAST first departure waits while a line is being said: within 18 ticks before or 10 after. */
    const val TALK_AFTER = 18
    const val TALK_BEFORE = 10

    /** Counted kills that defeat him, and the later throws after the four initial ones. */
    const val KILLS_TO_DEFEAT = 15
    const val LATER_THROWS = 11

    /** The pass line waits at most this many ticks for his chat queue. */
    const val PASS_WINDOW = 20

    /** First loop tick strictly after the door-relative tick [d]: where a death on [d] is counted. */
    fun loopTickAfter(d: Int): Int = Math.floorDiv(d, LOOP) * LOOP + LOOP

    fun isLoop(r: Int) = Math.floorMod(r, LOOP) == 0

    /** How fast he flies, by the kind of leg. */
    enum class Speed(val v: Double) { OPEN(V_OPEN), HOME(V_HOME), FAST(V_FAST), SLOW(V_SLOW) }

    /** One flight under the stop rule: the number of moving ticks and the rest point. The last moving tick is D + n, at rest D + n + 1. */
    data class Fly(val n: Int, val end: Vec3)

    /** The flight rule every Watcher flight follows: the opening hops, home and the later legs. */
    fun flyTo(p0: Vec3, target: Vec3, v: Double): Fly {
        val d = (target - p0).length()
        if (d * d < ARRIVE_R2) return Fly(0, p0)
        var k = maxOf(0, floor((d - sqrt(ARRIVE_R2)) / v).toInt() + 1)
        while (k > 0 && sq(d - (k - 1) * v) < ARRIVE_R2) k--
        while (sq(d - k * v) >= ARRIVE_R2) k++
        val dir = (target - p0) / d
        return Fly(k, p0 + dir * v * k.toDouble())
    }

    /** Where he aims for a wall station: one block above the head stand. */
    fun target(station: Vec3) = Vec3(station.x, station.y + 1.0, station.z)

    private fun sq(x: Double) = x * x

    // ---- the Watcher's chat queue ----------------------------------------------------------------------------------------------

    /** Line classes: how many ticks each keeps his first-in first-out chat queue busy. */
    enum class Line(val busy: Int) {
        REACT(42), SPAWN(62), HANDLE(62), ENOUGH(62), INTRO(42),

        /** Said when a player hits him, and when a player stands on the ledge out of reach: the camp itself never asks for these (it has no players), but they keep his queue busy like the others. */
        ATTACK(42), LEDGE(62),
    }

    /** A line asked for on tick [t]. */
    data class Request(val t: Int, val line: Line)

    /** A line printed on tick [at] that was asked for on tick [requested]. */
    data class Printed(val at: Int, val line: Line, val requested: Int)

    /** The queue is first in, first out: each line prints on the first tick that is at or after its request and at or after the end of the line before it. */
    fun chatLines(requests: List<Request>, until: Int): List<Printed> {
        val out = ArrayList<Printed>()
        var free = Int.MIN_VALUE / 2
        for (r in requests.sortedWith(compareBy({ it.t }, { if (it.line == Line.REACT) 0 else 1 }))) {
            val p = maxOf(r.t, free)
            if (p >= until) break
            out += Printed(p, r.line, r.t)
            free = p + r.line.busy
        }
        return out
    }

    /** Whether a line is being said around the first FAST departure tick [d]: one printed in [d-18, d], or one queued by [d] that prints in (d, d+10]. */
    fun talking(requests: List<Request>, d: Int): Boolean {
        val lines = chatLines(requests.filter { it.t <= d }, d + TALK_BEFORE + 1)
        return lines.any { (it.at in (d - TALK_AFTER)..d) || (it.at > d && it.at <= d + TALK_BEFORE && it.requested <= d) }
    }

    /** The tick the pass line prints for a defeat counted on loop tick [l]: on [l], or when the queue frees up within 20 ticks after it. */
    fun passTick(l: Int, lines: List<Printed>): Int {
        val prev = lines.lastOrNull { it.at < l } ?: return l
        val free = prev.at + prev.line.busy
        return if (free - l in 1..PASS_WINDOW) free else l
    }

    /**
     * When each recorded line was asked for. A line that printed while the queue was free was asked for on that tick. A line that
     * printed the moment the line before it ended (back to back) was asked for some time while that line was being said; the tick after
     * it started is taken. The two lines a player's action sets off (a hit on him, a player out of reach) are taken as asked for when
     * they printed.
     */
    fun requestsOf(printed: List<Request>): List<Request> {
        val out = ArrayList<Request>()
        var prev: Request? = null
        for (p in printed.sortedBy { it.t }) {
            val before = prev
            val free = before == null || p.t > before.t + before.line.busy || p.line == Line.ATTACK || p.line == Line.LEDGE
            out += Request(if (free) p.t else minOf(p.t, before!!.t + 1), p.line)
            prev = p
        }
        return out
    }

    /** The class of the Watcher's chat line, or null for a line that is not his (or is the pass line). A line of his that no class knows keeps the queue as busy as a reaction. */
    fun lineOf(chat: String): Line? {
        val prefix = "[BOSS] The Watcher: "
        if (!chat.startsWith(prefix)) return null
        val text = chat.removePrefix(prefix)
        return when {
            text.startsWith("You have proven yourself") -> null
            text == CampLines.INTRO || text == CampLines.INTRO_OTHER -> Line.INTRO
            text in CampLines.SPAWN -> Line.SPAWN
            text in CampLines.REACT -> Line.REACT
            text in CampLines.HANDLE -> Line.HANDLE
            text in CampLines.ENOUGH -> Line.ENOUGH
            text in CampLines.ATTACK -> Line.ATTACK
            text in CampLines.LEDGE -> Line.LEDGE
            else -> Line.REACT
        }
    }
}
