package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.run.PlacedRoom
import kotlin.math.exp
import kotlin.random.Random

/** Where a bot may wander in a room and how it gets from one place to another there. */
interface WalkArea {
    /** A place to wander to from [from], or null when there is nowhere to go. */
    fun wander(from: Pt, random: Random): Pt?

    /** The points to pass on the way from [a] to [b], the last one being where the walker ends up ([b], or the nearest place it can stand to it). */
    fun route(a: Pt, b: Pt): List<Pt>
}

/** What the planner asks for a room's floor. Null: the room has no block data, and the bots wander over the room's tiles. */
fun interface RoomAreas {
    fun of(room: PlacedRoom): WalkArea?
}

/** An axis-aligned piece of floor, world coordinates. */
class Rect(val x0: Double, val z0: Double, val x1: Double, val z1: Double) {
    val area: Double get() = (x1 - x0) * (z1 - z0)
}

/** A floor that is the room's tiles with nothing in them: wanders anywhere inside a margin of the walls and goes in straight lines. The stand-in when a room has no block data. */
class FreeArea(private val rects: List<Rect>) : WalkArea {
    override fun wander(from: Pt, random: Random): Pt? {
        if (rects.isEmpty()) return null
        var pick = random.nextDouble() * rects.sumOf { it.area }
        val r = rects.firstOrNull { pick -= it.area; pick < 0 } ?: rects.last()
        return Pt(r.x0 + random.nextDouble() * (r.x1 - r.x0), r.z0 + random.nextDouble() * (r.z1 - r.z0))
    }

    override fun route(a: Pt, b: Pt): List<Pt> = listOf(b)

    companion object {
        /** Blocks kept clear of the walls of a tile (a dungeon room is walled at its edge). */
        const val MARGIN = 3.0

        fun of(room: PlacedRoom): FreeArea = FreeArea(room.tiles.map { t ->
            val x = DungeonFrame.tileMin(t.x).toDouble(); val z = DungeonFrame.tileMin(t.z).toDouble()
            Rect(x + MARGIN, z + MARGIN, x + DungeonFrame.TILE_SIZE - MARGIN, z + DungeonFrame.TILE_SIZE - MARGIN)
        })
    }
}

/** A floor read from the room's blocks: wanders over what can be walked and goes round what is in the way. */
class GridArea(val grid: RoomGrid) : WalkArea {
    override fun wander(from: Pt, random: Random): Pt? = grid.randomPoint(from, random, WANDER_MIN, WANDER_MAX)

    /** No way round (the floor is in pieces): a straight line, and the body keeps out of the walls by itself. */
    override fun route(a: Pt, b: Pt): List<Pt> = grid.path(a, b) ?: listOf(b)

    companion object {
        /** How far the next place to wander to is, blocks (UNMEASURED). */
        const val WANDER_MIN = 4.0
        const val WANDER_MAX = 25.0
    }
}

/** Plans the way through a room round what is in it. */
object RouteExpander {
    /**
     * [route] with every stretch that lies inside a room replaced by the way round what is in that room ([areaAt] says the floor at a point, null for
     * none). The door points and the points where the route ends stay exactly as they are (a door is still a gate); only points between them are added.
     * A stretch to a spot the walker cannot reach (a door's gap, a room's middle behind a pillar) goes as far as it can by the floor and then straight on.
     */
    fun expand(route: List<RoutePoint>, areaAt: (Pt) -> WalkArea?): List<RoutePoint> {
        if (route.size < 2) return route
        val out = ArrayList<RoutePoint>(route.size + 8)
        out += route[0]
        for (i in 1 until route.size) {
            val a = route[i - 1].pt; val c = route[i].pt
            if (a.dist(c) > MIN_STRETCH) {
                val area = areaAt(a.lerp(c, 0.5))
                if (area != null) for (p in area.route(a, c)) if (p.dist(c) > 1e-6 && p.dist(out.last().pt) > 0.05) out += RoutePoint(p)
            }
            out += RoutePoint(c, route[i].gate)
        }
        return out
    }

    /** A stretch shorter than this is not worth routing (a step through a doorway). */
    const val MIN_STRETCH = 2.0
}

/**
 * How a teammate moves in a room: not stand-then-dash between secrets, but a slow wander. It picks a place (the room's next secret now and then, else
 * anywhere on the floor), goes there at a speed drawn per stretch (log-normal, median 0.38 blocks a tick, p90 1.3: MEASURED over 27,700 one-second windows of
 * loaded teammates), stops now and then, and picks again. A quarter of all seconds come out still (MEASURED 25.5%) and a third
 * slow (0.1 to 0.6 blocks a tick; MEASURED 36.7%). It fills the room's time exactly, so the act timings stay as the plan has them. Pure.
 */
object RoomWalk {
    /** The speed of a stretch is log-normal about this median, blocks a tick, scaled by the class's pace against the usual 1.2 (MEASURED: class medians 0.31 Tank to 0.42 Mage). */
    const val MEDIAN_BPT = 0.38
    const val SIGMA = 0.92
    const val MIN_BPT = 0.06
    const val MAX_BPT = 2.0

    /**
     * The chance that the next place is a secret (when one is left), UNMEASURED. A stretch is followed by a stop with chance [PAUSE_CHANCE] that lasts
     * [MIN_PAUSE] to [MAX_PAUSE] ticks: CALIBRATED so that about a quarter of all seconds come out still (MEASURED 25.5%); stops of up to two seconds would
     * leave fewer than one second in ten still, because a second only counts as still when the whole of it is.
     */
    const val SECRET_BIAS = 0.6
    const val PAUSE_CHANCE = 0.5
    const val MIN_PAUSE = 10
    const val MAX_PAUSE = 100

    /** The speed of one stretch for a class that moves at [classBpt] (the pack's moving speed, 1.2 for the usual). */
    fun stretchSpeed(random: Random, classBpt: Double): Double {
        val z = PlanMath.gauss(random)
        return (MEDIAN_BPT * (classBpt / 1.2).coerceIn(0.6, 1.5) * exp(SIGMA * z)).coerceIn(MIN_BPT, MAX_BPT)
    }

    /**
     * Legs that fill exactly [t0] to [t1] for a walker starting at [from]: wandering over [area] and visiting the room's [spots] (secret places) as it goes.
     * Returns the legs and where the walker ends up.
     */
    fun legs(t0: Int, t1: Int, from: Pt, spots: List<Pt>, classBpt: Double, area: WalkArea, random: Random): Pair<List<Leg>, Pt> {
        if (t1 <= t0) return emptyList<Leg>() to from
        val legs = ArrayList<Leg>()
        val left = spots.toMutableList()
        var t = t0
        var cur = from
        var rounds = 0
        while (t < t1 && rounds++ < MAX_ROUNDS) {
            val target: Pt = if (left.isNotEmpty() && random.nextDouble() < SECRET_BIAS) {
                // Mostly the nearest secret, sometimes the one after (a person does not sweep a room in order).
                val ordered = left.sortedBy { it.dist(cur) }
                val s = ordered[if (ordered.size > 1 && random.nextDouble() < 0.25) 1 else 0]
                left.remove(s); s
            } else area.wander(cur, random) ?: cur
            for (p in area.route(cur, target)) {
                val d = cur.dist(p)
                if (d < 0.05) { cur = p; continue }
                val ticks = maxOf(1, Math.ceil(d / stretchSpeed(random, classBpt)).toInt())
                if (t + ticks >= t1) {
                    // The room's time runs out part-way along this stretch.
                    val end = cur.lerp(p, (t1 - t).toDouble() / ticks)
                    legs += Leg(t, t1, cur, end); t = t1; cur = end
                    break
                }
                legs += Leg(t, t + ticks, cur, p); t += ticks; cur = p
            }
            if (t < t1 && random.nextDouble() < PAUSE_CHANCE) {
                val n = minOf(random.nextInt(MIN_PAUSE, MAX_PAUSE + 1), t1 - t)
                legs += Leg(t, t + n, cur, cur); t += n
            }
        }
        if (t < t1) legs += Leg(t, t1, cur, cur)
        return legs to cur
    }

    private const val MAX_ROUNDS = 400
}
