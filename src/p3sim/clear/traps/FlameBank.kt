package com.engineerclient.p3sim.clear.traps

import java.util.UUID
import kotlin.random.Random

/**
 * The flamethrowers of one part of a room. On every beat ([FlameStream.period], 8 ticks in every room) one or more of the emitters shoot: a sound at the
 * emitter, then a stream of flame [FlameStream.reach] blocks long that is live for [FlameStream.liveTicks] ticks; a player in the stream is hit (once per
 * shot). Which emitters fire is not measured beyond this: in most rooms one per beat, never the one that just fired when another can (so a pair alternates, as
 * the recorded Slime and Diagonal pairs do), and in the New Trap's pit one to three of those within [FlameRule.pickRange] of a player in it. The stream's
 * length and width are estimates (UNVERIFIED, from 20 recorded hits).
 *
 * Runs on the room's clock, so only while a human is inside the room; with a [FlameRule.region] only while one is inside the box. Pure.
 */
class FlameBank(val rule: FlameRule, val stream: FlameStream, seed: Long) : Hazard {
    private class Live(val emitter: Emitter, val start: Int, val box: DoubleArray) { val hit = HashSet<UUID>() }

    private val rng = Random(seed)
    private val phase = rng.nextInt(stream.period)
    private val live = ArrayList<Live>()
    private var last: Set<Int> = emptySet()

    override fun tick(clock: Int, players: List<PlayerPos>, out: HazardOut) {
        val it = live.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val age = clock - s.start
            if (age > stream.liveTicks) { it.remove(); continue }
            if (age < 1) continue
            out.flames += FlameFx(s.emitter, stream.reach * (age - 1) / stream.liveTicks, stream.reach * age / stream.liveTicks)
            for (p in players) if (overlaps(p, s.box) && s.hit.add(p.id)) out.hits += HazardHit(p.id, HazardKind.FLAME)
        }
        if (Math.floorMod(clock + phase, stream.period) != 0) return
        val awake = if (rule.region == null) players else players.filter { inRegion(it, rule.region) }
        if (awake.isEmpty()) return
        val range = rule.pickRange ?: stream.range
        val candidates = rule.emitters.indices.filter { i -> awake.any { near(it, rule.emitters[i], range) } }
        if (candidates.isEmpty()) return
        val n = (if (rule.perBeatMax > rule.perBeatMin) rng.nextInt(rule.perBeatMin, rule.perBeatMax + 1) else rule.perBeatMin).coerceAtMost(candidates.size)
        val fresh = candidates.filter { it !in last }
        val chosen = (if (fresh.size >= n) fresh else candidates).shuffled(rng).take(n)
        last = chosen.toSet()
        for (i in chosen) {
            val e = rule.emitters[i]
            live += Live(e, clock, box(e))
            out.cues += SoundCue(HazardSound.FLAME_SHOOT, e.x + 0.5 + e.dx * 0.5, e.y + 0.5 + e.dy * 0.5, e.z + 0.5 + e.dz * 0.5)
        }
    }

    /** The cells the stream of [e] fills: from the dispenser's face [FlameStream.reach] blocks out, [FlameStream.halfWidth] to each side, [FlameStream.height] tall. */
    fun box(e: Emitter): DoubleArray {
        val hw = stream.halfWidth; val r = stream.reach
        val x0: Double; val x1: Double; val y0: Double; val y1: Double; val z0: Double; val z1: Double
        when {
            e.dx != 0 -> { x0 = if (e.dx > 0) e.x + 1.0 else e.x - r; x1 = x0 + r; z0 = e.z + 0.5 - hw; z1 = e.z + 0.5 + hw; y0 = e.y.toDouble(); y1 = y0 + stream.height }
            e.dz != 0 -> { z0 = if (e.dz > 0) e.z + 1.0 else e.z - r; z1 = z0 + r; x0 = e.x + 0.5 - hw; x1 = e.x + 0.5 + hw; y0 = e.y.toDouble(); y1 = y0 + stream.height }
            else -> { y0 = if (e.dy > 0) e.y + 1.0 else e.y - r; y1 = y0 + r; x0 = e.x + 0.5 - hw; x1 = e.x + 0.5 + hw; z0 = e.z + 0.5 - hw; z1 = e.z + 0.5 + hw }
        }
        return doubleArrayOf(x0, y0, z0, x1, y1, z1)
    }

    private fun overlaps(p: PlayerPos, b: DoubleArray): Boolean =
        p.x + HALF > b[0] && p.x - HALF < b[3] && p.y + HEIGHT > b[1] && p.y < b[4] && p.z + HALF > b[2] && p.z - HALF < b[5]

    private fun inRegion(p: PlayerPos, r: IntArray): Boolean = p.x >= r[0] && p.x < r[3] + 1 && p.y >= r[1] - 0.5 && p.y < r[4] + 1 && p.z >= r[2] && p.z < r[5] + 1

    private fun near(p: PlayerPos, e: Emitter, range: Double): Boolean {
        val dx = p.x - (e.x + 0.5); val dz = p.z - (e.z + 0.5)
        return dx * dx + dz * dz <= range * range && Math.abs(p.y - e.y) <= 8.0
    }

    private companion object {
        const val HALF = 0.3
        const val HEIGHT = 1.8
    }
}
