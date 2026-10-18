package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.pack.rooms.RoomSecret
import com.engineerclient.p3sim.run.PlacedRoom
import kotlin.random.Random

/** A secret of a placed room as a bot sees it: what it is, where it is in the world. */
class SecretSpot(val secret: RoomSecret, val x: Int, val y: Int, val z: Int) {
    val pt: Pt get() = Pt(x + 0.5, z + 0.5)
}

/**
 * The plausible way through a room when no recorded traversal exists: a slow wander over the room's floor that now and then goes to one of its secrets
 * ([RoomWalk]), filling the room's measured duration. Pure.
 */
object RoomRoutes {
    /** The room's secrets in world coordinates (a design with no placement, or a headless plan without a room library, has none). At most [limit] are returned. */
    fun secretSpots(room: PlacedRoom, limit: Int = Int.MAX_VALUE): List<SecretSpot> {
        val design = room.design ?: return emptyList()
        val placement = room.placement ?: return emptyList()
        return design.secrets.take(limit.coerceAtLeast(0)).map { s -> placement.toWorld(s.pos.x, s.pos.y, s.pos.z).let { SecretSpot(s, it.x, it.y, it.z) } }
    }

    /** Spots in the order a walker would visit them, starting at [from]: always the nearest one left. */
    fun visitOrder(from: Pt, spots: List<Pt>): List<Pt> {
        val left = spots.toMutableList()
        val out = ArrayList<Pt>(spots.size)
        var cur = from
        while (left.isNotEmpty()) {
            val next = left.minBy { it.dist(cur) }
            left.remove(next); out += next; cur = next
        }
        return out
    }

    /**
     * Legs that fill exactly [t0] to [t1]: a wander over [area] (a box round [centre] when the room's floor is not known) with visits to [spots], at the
     * pace of a class that moves [bpt] (see [RoomWalk]). Returns the legs and where the walker ended up.
     */
    fun legs(t0: Int, t1: Int, from: Pt, spots: List<Pt>, centre: Pt, bpt: Double, random: Random, area: WalkArea? = null): Pair<List<Leg>, Pt> =
        RoomWalk.legs(t0, t1, from, spots, bpt, area ?: FreeArea(listOf(Rect(centre.x - 12.0, centre.z - 12.0, centre.x + 12.0, centre.z + 12.0))), random)
}
