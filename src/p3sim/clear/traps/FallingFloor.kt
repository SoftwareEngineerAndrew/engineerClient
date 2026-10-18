package com.engineerclient.p3sim.clear.traps

import com.engineerclient.p3sim.clear.puzzles.hard.BlockEdit
import com.google.gson.JsonObject

/**
 * A floor that lets go: when the trigger happens every block of the floor turns to a falling-block entity in the SAME tick (the blocks
 * become air, a lava-extinguish sound plays) and whoever stood on it drops into the room below. It happens once.
 *
 * The New Trap's whole entrance floor (143 blocks at y 68) goes the first time a player stands on any of it; the Old Trap's walkway (the
 * slabs beside the third string, the string itself and the block under it) goes when a player touches that string. Fall damage is not
 * dealt (health did not change in the recorded 5-block drop).
 *
 * [cells] are canonical `[x, y, z]`; [extra] cells (the string and the block under it) are cleared too but only the cells that hold a
 * block make debris. [trigger] decides when it goes.
 */
class FallingFloor(
    val cells: List<IntArray>,
    val extra: List<IntArray>,
    val trigger: FloorTrigger,
) : Hazard {
    /** Whether the floor has gone. */
    var fired: Boolean = false
        private set

    private val cellSet: Set<Long> = cells.mapTo(HashSet()) { cellKey(it[0], it[1], it[2]) }

    override fun tick(clock: Int, players: List<PlayerPos>, out: HazardOut) {
        if (fired) return
        val soundAt: IntArray = when (trigger) {
            is FloorTrigger.StandOn -> {
                val p = players.firstOrNull { standsOnFloor(it) } ?: return
                intArrayOf(Math.floor(p.x).toInt(), Math.floor(p.y + 1e-4).toInt() - 1, Math.floor(p.z).toInt())
            }
            is FloorTrigger.StringTouched -> {
                if (trigger.line !in out.touched) return
                // The recorded sound sat at the far end of the walkway, not at the string.
                cells.maxWithOrNull(compareBy<IntArray>({ it[0] }, { it[2] })) ?: return
            }
        }
        fired = true
        out.debris += cells
        out.debris += extra
        out.edits += clearEdits()
        out.cues += SoundCue(HazardSound.FLOOR_COLLAPSE, soundAt[0] + 0.5, soundAt[1] + 0.5, soundAt[2] + 0.5)
    }

    /** Whether the player's feet are on (or less than a block above) a cell of the floor. */
    fun standsOnFloor(p: PlayerPos): Boolean =
        cellKey(Math.floor(p.x).toInt(), Math.floor(p.y + 1e-4).toInt() - 1, Math.floor(p.z).toInt()) in cellSet

    private fun clearEdits(): List<BlockEdit> = (cells + extra).map { BlockEdit(it[0], it[1], it[2], Crusher.AIR) }

    override fun currentEdits(): List<BlockEdit> = if (fired) clearEdits() else emptyList()

    override fun snapshot(o: JsonObject) { o.addProperty("fired", fired) }

    override fun restore(o: JsonObject) { fired = o.get("fired")?.asBoolean ?: false }

    companion object {
        /** One number for a cell (20 bits a coordinate, so negative ones wrap but stay distinct). */
        fun cellKey(x: Int, y: Int, z: Int): Long = ((x.toLong() and 0xFFFFF) shl 40) or ((y.toLong() and 0xFFFFF) shl 20) or (z.toLong() and 0xFFFFF)

        /**
         * The connected part of [all] (cells that touch side to side or corner to corner on one level, same y) that holds the cell nearest to
         * [near]; every cell when [near] is null. A design's falling cells can hold more than one separate floor.
         */
        fun cluster(all: List<IntArray>, near: IntArray?): List<IntArray> {
            if (near == null || all.isEmpty()) return all
            val byKey = all.associateBy { cellKey(it[0], it[1], it[2]) }
            fun k(x: Int, y: Int, z: Int) = cellKey(x, y, z)
            val start = all.minByOrNull { Math.abs(it[0] - near[0]) + Math.abs(it[1] - near[1]) + Math.abs(it[2] - near[2]) } ?: return all
            val seen = HashSet<Long>()
            val out = ArrayList<IntArray>()
            val queue = ArrayDeque<IntArray>()
            queue += start; seen += k(start[0], start[1], start[2])
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                out += c
                for (dx in -1..1) for (dz in -1..1) {
                    if (dx == 0 && dz == 0) continue
                    val n = byKey[k(c[0] + dx, c[1], c[2] + dz)] ?: continue
                    if (seen.add(k(n[0], n[1], n[2]))) queue += n
                }
            }
            return out
        }
    }
}

/** What sets a [FallingFloor] off. */
sealed interface FloorTrigger {
    /** A human stands over any cell of the floor. */
    object StandOn : FloorTrigger

    /** A human touches the tripwire line with this index (in the room's [RoomHazards.lines]). */
    class StringTouched(val line: Int) : FloorTrigger
}
