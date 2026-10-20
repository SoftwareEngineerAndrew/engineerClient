package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.pack.rooms.RoomVolume
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

/** One teleport pad: its end-portal frame cell and the cell it lands you on, in the room's canonical frame. */
class Pad(val index: Int, val frameX: Int, val frameZ: Int, val landX: Int, val landZ: Int) {
    /** The group of four pads around one patch of floor; the two centre pads (28 and 29) belong to none. */
    val group: Int get() = if (index < 28) index / 4 else -1
    val centreX: Double get() = frameX + 0.5
    val centreZ: Double get() = frameZ + 0.5
}

/**
 * Where a player lands: the middle of the cell, on the slab ([x], [z] end in .5, [y] is the slab top), looking [yaw].
 * This is exactly what Odin's Teleport Maze solver recognises (a position packet with y 69.5 and x, z on half blocks).
 */
class Landing(val pad: Int, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

/**
 * The Teleport Maze as a state machine. The room has 30 end-portal-frame pads: 28 of them in seven groups of four (each group
 * stands around one patch of slab floor) and two in the middle. A pad sends you to a landing cell on another pad's patch; the
 * pad you land beside is part of a group, and one pad of that group is the way on. You always land looking at it, which is the
 * clue Odin's solver reads. The way on leads to the next group, the other pads send you elsewhere, and the last group's way on
 * leads to the middle pad 28, next to the chest. Pad 29 is the start.
 *
 * What the recordings settle: the landing cells (always the middle of the far corner of a pad's 2x2 patch, on top of the slab),
 * that each pad always sends you to the same place within one room, that a different room sends it somewhere else, and that every
 * walk ends on pad 28. What they do not: how the maze is drawn up (here: [generate] draws the route and the dead ends from a seed),
 * how many groups the route visits ([routeGroups]), and where the wrong pads send you.
 */
class TpMazeModel(val pads: List<Pad>, val frameY: Int, private val dest: IntArray, val correct: IntArray, val route: List<Int>) {
    /** Whether somebody has reached pad 28. */
    var solved: Boolean = false
        private set

    /** The pad a frame cell belongs to, or null. */
    fun padAtFrame(x: Int, z: Int): Pad? = pads.firstOrNull { it.frameX == x && it.frameZ == z }

    /** Sends [pad] to [to] from now on (a replay's recorded step). */
    fun send(pad: Int, to: Int) { if (pad in dest.indices && to in pads.indices) dest[pad] = to }

    /** The pad whose landing cell is (x, z), or null. */
    fun padLandingAt(x: Int, z: Int): Pad? = pads.firstOrNull { it.landX == x && it.landZ == z }

    /** The pad a stepped-on pad sends you to, or null when this pad (28) sends you nowhere. */
    fun destinationOf(pad: Int): Int? = dest[pad].takeIf { it >= 0 }

    /** The pad of the group of [pad] that is the way on (the one the landing yaw points at), or null for the middle pads. */
    fun wayOnFrom(pad: Int): Int? = pads[pad].group.takeIf { it >= 0 }?.let { correct[it] }

    /** The landing for stepping on [pad], or null when the pad does nothing. Reaching pad 28 marks the maze solved. */
    fun step(pad: Int): Landing? {
        val to = destinationOf(pad) ?: return null
        if (to == EXIT) solved = true
        return landing(to)
    }

    /** The landing on pad [to]'s patch, looking at the way on of its group (towards the chest for pad 28). */
    fun landing(to: Int): Landing {
        val p = pads[to]
        val x = p.landX + 0.5; val z = p.landZ + 0.5
        val aim = wayOnFrom(to)?.let { pads[it] }
        // The last pad has no way on: its landing looks at the reward chest (canonical 15.5, 20.5), as the recordings do.
        val yaw = if (aim != null) yawTo(aim.centreX - x, aim.centreZ - z) else yawTo(CHEST_X - x, CHEST_Z - z)
        return Landing(to, x, frameY + 0.5, z, yaw, 0f)
    }

    /** The pads you must step on, in order, from the start pad to the exit (what a player who reads every clue does). */
    fun solution(): List<Int> {
        val out = ArrayList<Int>()
        var p = START
        out += p
        var guard = 0
        while (p != EXIT && guard++ < 64) {
            val land = destinationOf(p) ?: break
            if (land == EXIT) break
            p = wayOnFrom(land) ?: break
            out += p
        }
        return out
    }

    companion object {
        const val EXIT = 28
        private const val CHEST_X = 15.5
        private const val CHEST_Z = 20.5
        const val START = 29
        const val GROUPS = 7

        /**
         * The 30 frame cells in Odin's order (its solver indexes pads this way: groups of four, then the two middle pads). The cells are
         * those of Odin's `TPMazeSolver` (BSD-3, Odin contributors); they were checked against the built room.
         */
        private val FRAMES = arrayOf(
            4 to 12, 4 to 6, 10 to 12, 10 to 6, 4 to 20, 4 to 14, 10 to 20, 10 to 14, 4 to 28, 4 to 22, 10 to 28, 10 to 22, 12 to 28, 12 to 22, 18 to 28, 18 to 22,
            20 to 28, 20 to 22, 26 to 28, 26 to 22, 26 to 20, 26 to 14, 20 to 20, 20 to 14, 26 to 12, 26 to 6, 20 to 12, 20 to 6, 15 to 14, 15 to 12,
        )

        /**
         * The pads of the room. A landing cell is the far corner of the 2x2 patch of slabs a frame stands in: one cell across and one along
         * from the frame, towards the middle of the patch. In the measured room that is +1 in x for frames at x = 4, 12, 20 and -1 for 10, 18, 26;
         * +1 in z for frames at z = 6, 14, 22 and -1 for 12, 20, 28; and for the two middle pads (16, 15) beside pad 28 and (14, 11) beside pad 29.
         */
        fun standardPads(): List<Pad> = FRAMES.mapIndexed { i, (x, z) ->
            val dx = if (i == EXIT) 1 else if (x % 8 == 4) 1 else -1
            val dz = if (z % 8 == 6) 1 else -1
            Pad(i, x, z, x + dx, z + dz)
        }

        /** The frame height of a room volume (the first end-portal frame found), or 69. */
        fun frameYOf(volume: RoomVolume): Int {
            for ((x, z) in FRAMES) for (y in volume.y0 until volume.y0 + volume.h) if (volume.stateAt(x, y, z).startsWith("minecraft:end_portal_frame")) return y
            return 69
        }

        /** MC yaw for a direction in x and z (looking along +z is yaw 0, along -x is yaw 90). */
        fun yawTo(dx: Double, dz: Double): Float = Math.toDegrees(atan2(-dx, dz)).toFloat()

        /**
         * Draws a maze from [seed]. The route visits [routeGroups] groups (2 to 7) in a random order; in each group one pad is the way on
         * (chosen so that from every landing the line of sight to it passes no other pad of the group, so a reader of the clue cannot
         * mistake it); the way on of a route group leads to a landing in the next route group, of the last one to pad 28; a group
         * off the route has its way on lead into the route; every other pad sends you to a landing on some pad that is not a way on.
         * Pad 29 leads into the first route group.
         */
        fun generate(pads: List<Pad>, frameY: Int, seed: Long, routeGroups: Int = DEFAULT_ROUTE_GROUPS): TpMazeModel {
            require(pads.size == 30)
            val rnd = Random(seed)
            val n = routeGroups.coerceIn(2, GROUPS)
            val order = (0 until GROUPS).shuffled(rnd)
            val route = order.take(n)
            val correct = IntArray(GROUPS) { g -> pickWayOn(pads, g, rnd) }
            val dest = IntArray(30) { -1 }
            val notWayOn = (0 until 28).filter { it != correct[pads[it].group] }
            fun landIn(group: Int) = (0 until 4).map { group * 4 + it }.filter { it != correct[group] }.random(rnd)
            for (g in 0 until GROUPS) {
                val idx = route.indexOf(g)
                val next = if (idx >= 0) route.getOrNull(idx + 1) else route.random(rnd)
                dest[correct[g]] = if (next == null) EXIT else landIn(next)
                for (p in g * 4 until g * 4 + 4) if (p != correct[g]) dest[p] = notWayOn.random(rnd)
            }
            dest[START] = landIn(route[0])
            dest[EXIT] = -1
            return TpMazeModel(pads, frameY, dest, correct, route)
        }

        /** Groups the route visits unless asked otherwise: the recorded walks take 3 to 7 steps, so four. Unmeasured. */
        const val DEFAULT_ROUTE_GROUPS = 7

        /** The pad of group [g] to make the way on: one that keeps every landing's line of sight to it clear of the group's other pads. */
        private fun pickWayOn(pads: List<Pad>, g: Int, rnd: Random): Int {
            val members = (g * 4 until g * 4 + 4).toList()
            val order = members.shuffled(rnd)
            for (c in order) if (members.filter { it != c }.all { lander -> clearLine(pads, lander, c, members) }) return c
            return order.first()
        }

        /** Looking from [lander]'s landing cell at [target]'s centre, no other pad of [members] lies within 1.6 blocks of the line (Odin's box is 0.75 around a pad). */
        private fun clearLine(pads: List<Pad>, lander: Int, target: Int, members: List<Int>): Boolean {
            val lx = pads[lander].landX + 0.5; val lz = pads[lander].landZ + 0.5
            val tx = pads[target].centreX; val tz = pads[target].centreZ
            val len = hypot(tx - lx, tz - lz)
            if (len < 1e-6) return false
            for (m in members) {
                if (m == lander || m == target) continue
                val mx = pads[m].centreX - lx; val mz = pads[m].centreZ - lz
                val along = (mx * (tx - lx) + mz * (tz - lz)) / len
                if (along < 0) continue
                val across = Math.abs(mx * (tz - lz) - mz * (tx - lx)) / len
                if (across < 1.6) return false
            }
            return true
        }
    }
}
