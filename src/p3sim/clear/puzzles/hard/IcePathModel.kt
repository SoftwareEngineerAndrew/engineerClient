package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.pack.rooms.RoomVolume

/**
 * The Ice Path as a state machine. A silverfish stands in the middle of a cell on a sheet of ice. Hit it and it slides away from
 * you along the nearer axis, at [SPEED] blocks a tick, until the next cell is blocked, and stands still again. It cannot be hit while it
 * moves, and a hit towards a wall does nothing. Get it into the exit corridor and the puzzle is solved; slide it into a trap and
 * it fails.
 *
 * What the recordings settle: the start cell, the speed (0.4 blocks a tick, steady, no easing), that it stops in the middle of the cell
 * before an obstacle, the two refusal lines, and that the fish disappears when it reaches the last row of the arena on its way into the
 * exit corridor. What they do not: where the traps are. They are the cells next to the four side hoppers of the room ([IcePathLayout.read]
 * puts a trap by each); that is a guess from the room's blocks, and only one fail in about fifty rooms was ever recorded.
 *
 * Cells are (x, z) blocks of the room's canonical frame; positions are block coordinates, a cell's middle at +0.5. Pure: no Minecraft.
 */
class IcePathModel(
    private val solid: (Int, Int) -> Boolean,
    val startX: Int,
    val startZ: Int,
    /** The corridor columns the fish must be sliding south in, and the row where the goal counts. */
    private val goalXs: IntRange,
    private val goalZ: Int,
    private val traps: Set<Long> = emptySet(),
) {
    enum class Phase { IDLE, MOVING, SOLVED, TRAPPED }

    enum class Hit { STARTED, WHILE_MOVING, BLOCKED, IGNORED }

    var phase: Phase = Phase.IDLE
        private set
    var cellX: Int = startX
        private set
    var cellZ: Int = startZ
        private set

    private var dx = 0
    private var dz = 0
    private var progress = 0.0

    /** The fish's position, block coordinates. */
    val x: Double get() = cellX + 0.5 + dx * progress
    val z: Double get() = cellZ + 0.5 + dz * progress

    /** Heading in x and z while moving (0, 0 when still). */
    val headingX: Int get() = if (phase == Phase.MOVING) dx else 0
    val headingZ: Int get() = if (phase == Phase.MOVING) dz else 0

    /** Puts the fish back on its start cell, standing. */
    fun reset() { phase = Phase.IDLE; cellX = startX; cellZ = startZ; dx = 0; dz = 0; progress = 0.0 }

    /** A hit by someone standing at ([fromX], [fromZ]) (block coordinates): the fish slides away along the nearer axis. */
    fun hit(fromX: Double, fromZ: Double): Hit {
        if (phase == Phase.MOVING) return Hit.WHILE_MOVING
        if (phase != Phase.IDLE) return Hit.IGNORED
        val ax = x - fromX; val az = z - fromZ
        val sx: Int; val sz: Int
        if (Math.abs(ax) >= Math.abs(az)) { sx = if (ax >= 0) 1 else -1; sz = 0 } else { sx = 0; sz = if (az >= 0) 1 else -1 }
        if (solid(cellX + sx, cellZ + sz)) return Hit.BLOCKED
        dx = sx; dz = sz; progress = 0.0; phase = Phase.MOVING
        return Hit.STARTED
    }

    /** One tick of sliding; returns the phase the fish is in afterwards (SOLVED and TRAPPED are final until [reset]). */
    fun tick(): Phase {
        if (phase != Phase.MOVING) return phase
        progress += SPEED
        while (progress >= 1.0 && phase == Phase.MOVING) {
            progress -= 1.0
            cellX += dx; cellZ += dz
            if (dz > 0 && cellX in goalXs && cellZ >= goalZ) { phase = Phase.SOLVED; settle(); return phase }
            if (key(cellX, cellZ) in traps) { phase = Phase.TRAPPED; settle(); return phase }
            if (solid(cellX + dx, cellZ + dz)) { phase = Phase.IDLE; settle() }
        }
        return phase
    }

    private fun settle() { dx = 0; dz = 0; progress = 0.0 }

    /** The slides that solve it from the start, shortest first, as the cells it stops on (for tests and `/ads dev`), or null if there is none. */
    fun solution(): List<Pair<Int, Int>>? {
        val start = startX to startZ
        val prev = HashMap<Pair<Int, Int>, Pair<Int, Int>?>()
        prev[start] = null
        val queue = ArrayDeque<Pair<Int, Int>>(); queue += start
        var goal: Pair<Int, Int>? = null
        while (queue.isNotEmpty() && goal == null) {
            val p = queue.removeFirst()
            for ((sx, sz) in DIRS) {
                var x = p.first; var z = p.second; var n = 0; var reached = false
                while (!solid(x + sx, z + sz)) {
                    x += sx; z += sz; n++
                    if (key(x, z) in traps) { n = 0; break }
                    if (sz > 0 && x in goalXs && z >= goalZ) { reached = true; break }
                    if (n > 64) break
                }
                if (n == 0) continue
                val q = x to z
                if (q in prev) continue
                prev[q] = p
                if (reached) { goal = q; break }
                queue += q
            }
        }
        val g = goal ?: return null
        val out = ArrayList<Pair<Int, Int>>()
        var c: Pair<Int, Int>? = g
        while (c != null) { out += c; c = prev[c] }
        out.reverse()
        return out.drop(1)
    }

    companion object {
        /** Blocks a tick (steady in every recording). */
        const val SPEED = 0.4

        private val DIRS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        fun key(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)

        const val MSG_MOVING = "You cannot hit the silverfish while it's moving!"
        const val MSG_BLOCKED = "You cannot move the silverfish in that direction!"
        const val MSG_FAIL = "pushed the silverfish into a Trap! That's a whoopsie!"
    }
}

/** Where the Ice Path's cells are, read from the room's blocks (canonical frame). */
class IcePathLayout(val model: () -> IcePathModel, val floorY: Int, val start: Pair<Int, Int>) {
    companion object {
        const val ARENA_MIN_X = 7; const val ARENA_MAX_X = 23
        const val ARENA_MIN_Z = 8; const val ARENA_MAX_Z = 24
        val CORRIDOR_X = 14..16
        const val CORRIDOR_MAX_Z = 28
        const val START_X = 8; const val START_Z = 9

        /** The fish's feet stand on top of the ice at this y (66 is the ice sheet). */
        const val DEFAULT_FLOOR_Y = 67

        /**
         * Reads the room: a cell is blocked when the block at the fish's height is not air, or when it lies outside the arena and the exit
         * corridor. Traps: a cell beside every hopper that has fire above it, other than the exit corridor's own hopper, at the nearest
         * arena cell (see the class note of [IcePathModel]: a guess).
         */
        fun read(volume: RoomVolume): IcePathLayout {
            val floorY = DEFAULT_FLOOR_Y
            fun inArena(x: Int, z: Int) = x in ARENA_MIN_X..ARENA_MAX_X && z in ARENA_MIN_Z..ARENA_MAX_Z
            fun inCorridor(x: Int, z: Int) = x in CORRIDOR_X && z in (ARENA_MAX_Z + 1)..CORRIDOR_MAX_Z
            val blocked = { x: Int, z: Int -> !(inArena(x, z) || inCorridor(x, z)) || !volume.isAir(x, floorY, z) }
            val traps = HashSet<Long>()
            for (y in volume.y0 until volume.y0 + volume.h) for (z in 0 until volume.d) for (x in 0 until volume.w) {
                if (!volume.stateAt(x, y, z).startsWith("minecraft:hopper")) continue
                if (!volume.stateAt(x, y + 1, z).startsWith("minecraft:fire")) continue
                if (inCorridor(x, z.coerceIn(ARENA_MIN_Z, CORRIDOR_MAX_Z))) continue
                traps += IcePathModel.key(x.coerceIn(ARENA_MIN_X, ARENA_MAX_X), z.coerceIn(ARENA_MIN_Z, ARENA_MAX_Z))
            }
            return IcePathLayout({ IcePathModel(blocked, START_X, START_Z, CORRIDOR_X, ARENA_MAX_Z, traps) }, floorY, START_X to START_Z)
        }
    }
}
