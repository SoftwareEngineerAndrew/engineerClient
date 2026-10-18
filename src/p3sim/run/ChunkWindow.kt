package com.engineerclient.p3sim.run

/**
 * The window of chunks the game keeps for a player at a view distance, as `ChunkTrackingView` computes it: a chunk (dx, dz) from the player's own is
 * tracked when `max(0, |dx| - 2)^2 + max(0, |dz| - 2)^2 < distance^2`. Pure; it is here to say why [ViewPin.DISTANCE] is what it is: Hypixel's window is 15 chunks
 * wide with 211 to 225 chunks in steady state (97 sessions), and 6 is the one distance that gives 15 wide and 213 chunks (5 gives 13 and 157, 7 gives 17 and 265).
 */
object ChunkWindow {
    /** Whether the chunk [dx], [dz] chunks from the player's is in the window of [distance]. */
    fun tracked(dx: Int, dz: Int, distance: Int): Boolean {
        val x = maxOf(0, Math.abs(dx) - 2).toLong(); val z = maxOf(0, Math.abs(dz) - 2).toLong()
        return x * x + z * z < distance.toLong() * distance
    }

    /** How many chunks the window of [distance] holds. */
    fun count(distance: Int): Int {
        val r = distance + 3
        var n = 0
        for (dx in -r..r) for (dz in -r..r) if (tracked(dx, dz, distance)) n++
        return n
    }

    /** How many chunks wide the window of [distance] is along an axis through the player. */
    fun width(distance: Int): Int = 2 * (0..distance + 3).last { tracked(it, 0, distance) } + 1

    /**
     * The chunks, as offsets from the centre, of the window of [loginDistance] that [kept] says are not wanted any more: what a client still holds after
     * the player moved to a window it leaves out. The login's window is round the world's spawn, so the offsets are chunk coordinates there.
     */
    fun leftBehind(loginDistance: Int, kept: (Int, Int) -> Boolean): List<Pair<Int, Int>> {
        val r = loginDistance + 3
        val out = ArrayList<Pair<Int, Int>>()
        for (dx in -r..r) for (dz in -r..r) if (tracked(dx, dz, loginDistance) && !kept(dx, dz)) out += dx to dz
        return out
    }
}
