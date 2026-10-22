package com.engineerclient.p3sim.clear.bots

import kotlin.random.Random

/**
 * The bots before the clock starts. A real teammate appears on the very block the player spawns on (the same x, y and z, about 70 ticks before the
 * "Starting in 4 seconds" line), drops to the floor, and walks straight across the entrance room to the door that leads on: by the first Mort line
 * it has covered 15 to 18 blocks (p5 0, p25 15.2, p50 16.2, p75 18.2) and stands about 1 to 2 blocks inside the door, sideways within 1.2 blocks of
 * where it started, having moved in 53% of the ticks on the way (41 sessions, 73 teammates). Pure.
 */
object Countdown {
    /** A walking player's speed, blocks a tick (vanilla walking 4.317 blocks a second). */
    const val WALK_BPT = 0.2158

    /** Ticks a teammate spends falling from the spawn height (75.5 or 76.5) to the floor (69) before it can walk. */
    const val FALL_TICKS = 12

    /** How far inside the room, from the door's gap, a bot waits at the end of the countdown (MEASURED: they end 1.3 to 2.3 blocks short of the door's line). */
    const val GATE_INSET = 2.0

    /** How far sideways of its spawn column a bot ends up, either way (MEASURED: within 1.2 blocks). */
    const val LATERAL = 1.2

    /** Changes the pace of one walker by this much either way, so four bots do not move in step. UNMEASURED. */
    const val SPEED_SPREAD = 0.15

    /** The fastest a countdown walk goes when the time is short (a sprint, 0.28 blocks a tick). */
    const val MAX_BPT = 0.28

    /** Where a bot waits for the clock: [inset] blocks inside the room from the door's gap, in line with [spawn], sideways by a draw within [LATERAL]. */
    fun gate(doorSpot: Pt, spawn: Pt, random: Random, inset: Double = GATE_INSET): Pt {
        val dx = spawn.x - doorSpot.x; val dz = spawn.z - doorSpot.z
        val len = Math.hypot(dx, dz)
        if (len < 0.5) return spawn
        val ux = dx / len; val uz = dz / len
        val side = (random.nextDouble() * 2.0 - 1.0) * LATERAL
        val back = minOf(inset, len)
        return Pt(doorSpot.x + ux * back - uz * side, doorSpot.z + uz * back + ux * side)
    }

    /**
     * The legs of one bot from tick [fromT] (negative) to 0: it stands where it appeared while it falls and looks about, then walks to [gate] in two
     * or three stretches with pauses, and waits there. Fills [fromT] to 0 exactly; empty when there is no time.
     */
    fun legs(spawn: Pt, gate: Pt, fromT: Int, random: Random): List<Leg> {
        val window = -fromT
        if (window <= 0) return emptyList()
        val dist = spawn.dist(gate)
        val fall = minOf(FALL_TICKS, window / 3)
        val avail = window - fall
        if (dist < 0.3) return listOf(Leg(fromT, 0, spawn, spawn))
        var speed = WALK_BPT * (1.0 - SPEED_SPREAD + 2 * SPEED_SPREAD * random.nextDouble())
        val stretches = if (dist / speed >= 24 && random.nextDouble() < 0.5) 3 else 2
        // Where the stretches end along the line: random cuts of the walk, in order, none shorter than a sixth of it.
        val cuts = (1 until stretches).map { 0.17 + 0.66 * random.nextDouble() }.sorted().let { c ->
            if (stretches == 3 && c[1] - c[0] < 0.17) listOf(0.33, 0.66) else c
        }
        val ends = cuts + 1.0
        val points = ends.map { if (it >= 1.0) gate else spawn.lerp(gate, it) }
        fun walkTicks(v: Double): Int {
            var prev = spawn; var sum = 0
            for (p in points) { sum += maxOf(1, Math.ceil(prev.dist(p) / v).toInt()); prev = p }
            return sum
        }
        // Short of time: hurry, up to a sprint, so the walk takes at most 90% of what is left.
        while (walkTicks(speed) > avail * 0.9 && speed < MAX_BPT) speed = minOf(MAX_BPT, speed * 1.08)
        val walk = walkTicks(speed)
        if (walk > avail) return squeeze(spawn, gate, fromT)

        val standing = avail - walk
        // The standing time: some before the first step, a pause between stretches, the rest at the gate.
        val first = (standing * (0.1 + 0.25 * random.nextDouble())).toInt()
        val pauses = (1 until stretches).map { (standing * (0.1 + 0.2 * random.nextDouble())).toInt() }

        val legs = ArrayList<Leg>()
        var t = fromT
        var cur = spawn
        fun stand(n: Int) { if (n > 0) { legs += Leg(t, t + n, cur, cur); t += n } }
        stand(fall + first)
        for ((i, to) in points.withIndex()) {
            val ticks = maxOf(1, Math.ceil(cur.dist(to) / speed).toInt())
            legs += Leg(t, t + ticks, cur, to); t += ticks; cur = to
            if (i < pauses.size) stand(pauses[i])
        }
        // The rest is settled at the gate: the legs end exactly at 0.
        if (t < 0) legs += Leg(t, 0, cur, cur)
        return legs
    }

    /** A plain stand-then-walk for a window too short for the real thing; still fills the time and ends at the gate. */
    private fun squeeze(spawn: Pt, gate: Pt, fromT: Int): List<Leg> {
        val window = -fromT
        val walk = (window * 0.8).toInt().coerceAtLeast(1)
        val wait = window - walk
        return if (wait > 0) listOf(Leg(fromT, fromT + wait, spawn, spawn), Leg(fromT + wait, 0, spawn, gate)) else listOf(Leg(fromT, 0, spawn, gate))
    }
}

/**
 * A bot dropping onto the floor after it appears in the air: vanilla gravity, 0.08 blocks per tick squared with 2% drag. Pure; the module feeds it
 * the floor height under the bot each tick.
 */
class Fall(var y: Double) {
    private var vy = 0.0

    /** True once the bot has reached the floor. */
    var landed = false
        private set

    /** One tick of falling; the bot lands on [floor]. Returns the new height. */
    fun step(floor: Double): Double {
        if (landed) return y
        vy = (vy - 0.08) * 0.98
        y += vy
        if (y <= floor) { y = floor; vy = 0.0; landed = true }
        return y
    }
}
