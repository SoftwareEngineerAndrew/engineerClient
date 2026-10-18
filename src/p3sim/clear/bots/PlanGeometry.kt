package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.PlacedRoom
import kotlin.math.hypot
import kotlin.math.min

/** A point on the dungeon floor, world x and z (the height is the floor under it, found when a body stands there). */
data class Pt(val x: Double, val z: Double) {
    fun dist(o: Pt): Double = hypot(x - o.x, z - o.z)

    fun lerp(o: Pt, f: Double): Pt = Pt(x + (o.x - x) * f, z + (o.z - z) * f)
}

/** One point of a route; [gate] is the door index when the point is a door spot the walker has to pass (it may be closed). */
data class RoutePoint(val pt: Pt, val gate: Int? = null)

/**
 * Where things are in a [DungeonPlan], as a walker needs them: room middles, door spots, the door graph, the route between two
 * rooms and which rooms can be reached by when. Pure (no Minecraft), so the ClearPlan and its tests run headless.
 */
class PlanGeometry(val plan: DungeonPlan) {
    /** An edge of the door graph: the room on the other side and the door between. */
    class Edge(val to: Int, val door: Int)

    /** Connected doors only (a wall slot is not an edge). The graph is a tree in a complete layout. */
    val adjacency: Map<Int, List<Edge>> = buildMap<Int, MutableList<Edge>> {
        for (d in plan.doors) if (d.kind.connected && d.roomA != d.roomB) {
            getOrPut(d.roomA) { ArrayList() } += Edge(d.roomB, d.index)
            getOrPut(d.roomB) { ArrayList() } += Edge(d.roomA, d.index)
        }
    }

    private val centres: Map<Int, Pt> = plan.rooms.associate { it.index to computeCentre(it) }

    /** The middle of a room: the middle of its tile centres, or the tile of the room nearest to it when that falls in an empty corner (an L). */
    fun centre(room: Int): Pt = centres[room] ?: Pt(DungeonFrame.tileCentre(3) + 0.5, DungeonFrame.tileCentre(3) + 0.5)

    private fun computeCentre(r: PlacedRoom): Pt {
        val tc = r.tiles.map { Pt(DungeonFrame.tileCentre(it.x) + 0.5, DungeonFrame.tileCentre(it.z) + 0.5) }
        if (tc.isEmpty()) return Pt(0.0, 0.0)
        val mean = Pt(tc.sumOf { it.x } / tc.size, tc.sumOf { it.z } / tc.size)
        // A gap column between two tiles of one room is open floor and belongs to the lower tile; the empty corner of an L belongs to no tile of the room.
        val tile = Tile(DungeonFrame.tileIndex(mean.x), DungeonFrame.tileIndex(mean.z))
        return if (tile in r.tiles) mean else tc.minBy { it.dist(mean) }
    }

    /** The room whose tiles hold the point, or null in a gap column, outside the dungeon, or in no room. */
    fun roomAt(p: Pt): PlacedRoom? {
        val tile = DungeonFrame.tileOf(p.x, p.z) ?: return null
        return plan.rooms.firstOrNull { tile in it.tiles }
    }

    /** The centre of a door's gap column. */
    fun doorPt(door: Int): Pt {
        val d = plan.doors[door]
        val (x, z) = d.spot
        return Pt(x + 0.5, z + 0.5)
    }

    /** The rooms from [a] to [b] along the door graph (inclusive), or just [a] when they are not connected. */
    fun roomPath(a: Int, b: Int): List<Int> {
        if (a == b) return listOf(a)
        val prev = HashMap<Int, Int>()
        val queue = ArrayDeque<Int>().also { it.add(a) }
        prev[a] = a
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == b) break
            for (e in adjacency[cur].orEmpty()) if (e.to !in prev) { prev[e.to] = cur; queue.add(e.to) }
        }
        if (b !in prev) return listOf(a)
        val out = ArrayList<Int>()
        var c = b
        while (c != a) { out += c; c = prev.getValue(c) }
        out += a
        return out.reversed()
    }

    /** The door between two neighbouring rooms in the graph. */
    fun doorBetween(a: Int, b: Int): Int? = adjacency[a]?.firstOrNull { it.to == b }?.door

    /**
     * The route from a point in room [fromRoom] to a point in room [toRoom] (default: its middle): the starting point, each door spot
     * on the way (with its door as the gate) and each room's middle between them.
     */
    fun route(from: Pt, fromRoom: Int, toRoom: Int, to: Pt? = null): List<RoutePoint> {
        val rooms = roomPath(fromRoom, toRoom)
        val out = ArrayList<RoutePoint>()
        out += RoutePoint(from)
        for (i in 1 until rooms.size) {
            val door = doorBetween(rooms[i - 1], rooms[i])
            if (door != null) out += RoutePoint(doorPt(door), door)
            if (i < rooms.size - 1) out += RoutePoint(centre(rooms[i]))
        }
        out += RoutePoint(to ?: centre(toRoom))
        return out
    }

    /** Length of a route, blocks. */
    fun length(route: List<RoutePoint>): Double = (1 until route.size).sumOf { route[it - 1].pt.dist(route[it].pt) }

    /** Doors on the way from room [a] to room [b]. */
    fun doorsOnRoute(a: Int, b: Int): List<Int> = roomPath(a, b).zipWithNext().mapNotNull { (x, y) -> doorBetween(x, y) }

    /**
     * For every room, the run tick from which it can be walked into: the latest passable time of any door between it and the entrance.
     * [doorPassableAt] gives a door's passable tick (0 for a plain open doorway). Rooms not connected to the entrance get [Int.MAX_VALUE].
     */
    fun reachTimes(entrance: Int, doorPassableAt: (Int) -> Int): IntArray {
        val reach = IntArray((plan.rooms.maxOfOrNull { it.index } ?: 0) + 1) { Int.MAX_VALUE }
        val queue = ArrayDeque<Int>()
        reach[entrance] = 0
        queue.add(entrance)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (e in adjacency[cur].orEmpty()) {
                val t = maxOf(reach[cur], doorPassableAt(e.door))
                if (reach[e.to] == Int.MAX_VALUE || t < reach[e.to]) { reach[e.to] = t; queue.add(e.to) }
            }
        }
        return reach
    }

    /** A spot [index] of [count] around [centre] (a small ring), so bots in one room do not stand on each other. */
    fun spread(centre: Pt, index: Int, count: Int, radius: Double = 1.6): Pt {
        if (count <= 1) return centre
        val a = 2.0 * Math.PI * index / count
        return Pt(centre.x + radius * kotlin.math.cos(a), centre.z + radius * kotlin.math.sin(a))
    }

    companion object {
        /** Blocks per tick needed to cover [length] in [ticks] (infinite for zero ticks). */
        fun speedFor(length: Double, ticks: Int): Double = if (ticks <= 0) Double.POSITIVE_INFINITY else length / ticks

        /** Ticks to cover [length] at [bpt], at least 0. */
        fun ticksFor(length: Double, bpt: Double): Int = if (length <= 0.0) 0 else Math.ceil(length / min(bpt, 1e9)).toInt()
    }
}
