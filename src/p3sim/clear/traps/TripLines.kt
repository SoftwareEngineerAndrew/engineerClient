package com.engineerclient.p3sim.clear.traps

/** A tripwire hook as the rooms pack lists it: its cell and the way it faces (`east`, `west`, `north`, `south`). */
class Hook(val x: Int, val y: Int, val z: Int, val facing: String)

/**
 * A tripwire stretched between two hooks: the cells strictly between them, at the hooks' height. [axis] is `x` when the line runs
 * east-west (z is [fixed]), `z` when it runs north-south (x is [fixed]); [from] and [to] are the hooks' coordinates along it.
 */
class TripLine(val axis: Char, val y: Int, val fixed: Int, val from: Int, val to: Int) {
    /** Whether a player standing at feet ([px], [py], [pz]) touches the string: their box meets the string's flat box (the bottom 0.15625 of each cell). */
    fun touches(px: Double, py: Double, pz: Double): Boolean {
        if (py + PLAYER_HEIGHT <= y || py >= y + STRING_HEIGHT) return false
        val along = if (axis == 'x') px else pz
        val across = if (axis == 'x') pz else px
        return along + PLAYER_HALF > from + 1 && along - PLAYER_HALF < to && across + PLAYER_HALF > fixed && across - PLAYER_HALF < fixed + 1
    }

    /** Whether the cell (x, y, z) is one of this line's two hooks. */
    fun hasHook(x: Int, y: Int, z: Int): Boolean =
        y == this.y && if (axis == 'x') z == fixed && (x == from || x == to) else x == fixed && (z == from || z == to)

    companion object {
        const val STRING_HEIGHT = 0.15625
        const val PLAYER_HEIGHT = 1.8
        const val PLAYER_HALF = 0.3

        /**
         * Pairs hooks into lines: a hook facing `east` or `south` is joined to the nearest hook ahead of it on the same line that faces
         * back (`west` or `north`). A hook with no partner makes no line.
         */
        fun pair(hooks: List<Hook>): List<TripLine> {
            val out = ArrayList<TripLine>()
            for (h in hooks) {
                when (h.facing) {
                    "east" -> hooks.filter { it.facing == "west" && it.y == h.y && it.z == h.z && it.x > h.x }.minByOrNull { it.x }
                        ?.let { out += TripLine('x', h.y, h.z, h.x, it.x) }
                    "south" -> hooks.filter { it.facing == "north" && it.y == h.y && it.x == h.x && it.z > h.z }.minByOrNull { it.z }
                        ?.let { out += TripLine('z', h.y, h.x, h.z, it.z) }
                }
            }
            return out
        }
    }
}
