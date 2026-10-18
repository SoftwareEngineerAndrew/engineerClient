package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.pack.mobs.HitQuantiles
import com.engineerclient.p3sim.pack.mobs.HpScaleTable
import com.engineerclient.p3sim.pack.mobs.MeleeTable
import com.engineerclient.p3sim.pack.mobs.MobCell
import com.engineerclient.p3sim.pack.mobs.MobModifier
import com.engineerclient.p3sim.pack.mobs.RoomMobs
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/** One mob a room's table says to spawn: the archetype (its index in the mob pack), whether it is starred, and its feet block in the room's canonical frame. */
data class RolledMob(val arch: Int, val star: Boolean, val x: Int, val y: Int, val z: Int)

/** How a room's mobs are drawn (FORMAT-mobs 5.1 and 5.2). */
enum class SpawnMode {
    /** Replay one whole recorded visit: keeps clusters, the mix of kinds and the counts together. The default. */
    LAYOUTS,
    /** Every spawn cell rolls on its own with the chance the visits gave it. */
    CELLS,
}

/**
 * The random draws of the mob tables, all pure and seeded by the caller (`run.subRandom("mobs...")`), so the same seed gives
 * the same rooms, HP scale and modifiers. Nothing here reads a setting: the caller passes the values in.
 */
object MobRolls {
    /** A recorded visit with fewer than half the usual starred mobs is a partial view (the recorder only sees mobs in view): it is drawn a quarter as often. FORMAT-mobs 5.2. */
    const val PARTIAL_LAYOUT_WEIGHT = 0.25

    /** With fewer recorded layouts than this for the floor a room falls back to independent cells. FORMAT-mobs 5.2. */
    const val MIN_LAYOUTS = 3

    /** Draws a room's mobs. [floor] is `F7` or `M7`. A room with no layouts for the floor falls back to cells whatever [mode] says. */
    fun rollRoom(room: RoomMobs, floor: String, rng: Random, mode: SpawnMode = SpawnMode.LAYOUTS, partialWeight: Double = PARTIAL_LAYOUT_WEIGHT): List<RolledMob> {
        if (mode == SpawnMode.LAYOUTS) rollLayout(room, floor, rng, partialWeight)?.let { return it }
        return rollCells(room, rng)
    }

    /** The "layouts" sampler: one recorded visit, or null when the room has fewer than [MIN_LAYOUTS] for the floor. */
    fun rollLayout(room: RoomMobs, floor: String, rng: Random, partialWeight: Double = PARTIAL_LAYOUT_WEIGHT): List<RolledMob>? {
        val pool = room.layouts.filter { it.fl == floor || it.fl == "?" }
        if (pool.size < MIN_LAYOUTS) return null
        val usual = (room.starred?.p50 ?: 0.0) / 2.0
        val weights = pool.map { l -> if (l.mobs.count { it.star } < usual) partialWeight else 1.0 }
        val total = weights.sum()
        if (total <= 0.0) return null
        var pick = rng.nextDouble() * total
        var chosen = pool.last()
        for ((i, w) in weights.withIndex()) { pick -= w; if (pick < 0.0) { chosen = pool[i]; break } }
        return chosen.mobs.map { RolledMob(it.arch, it.star, it.x, it.y, it.z) }
    }

    /**
     * The "cells" sampler: group the rows by (x, y, z, star); a group spawns with probability `min(1, sum(occ) / n)` and, if it does,
     * picks its archetype with probability `occ / sum(occ)`. Expected starred count of the room is [expectedStarred].
     */
    fun rollCells(room: RoomMobs, rng: Random): List<RolledMob> {
        val n = room.visits.coerceAtLeast(1)
        val out = ArrayList<RolledMob>()
        for ((key, group) in cellGroups(room.cells)) {
            val sum = group.sumOf { it.occ }
            if (sum <= 0) continue
            if (rng.nextDouble() >= minOf(1.0, sum.toDouble() / n)) continue
            var pick = rng.nextInt(sum)
            var chosen = group.last()
            for (c in group) { pick -= c.occ; if (pick < 0) { chosen = c; break } }
            out += RolledMob(chosen.arch, key.star, key.x, key.y, key.z)
        }
        return out
    }

    private data class CellKey(val x: Int, val y: Int, val z: Int, val star: Boolean)

    private fun cellGroups(cells: List<MobCell>): Map<CellKey, List<MobCell>> = cells.groupBy { CellKey(it.x, it.y, it.z, it.star) }

    /** The mean starred count the "cells" sampler gives for a room: the sum over starred cell groups of `min(1, sum(occ) / n)`. */
    fun expectedStarred(room: RoomMobs): Double {
        val n = room.visits.coerceAtLeast(1)
        return cellGroups(room.cells).filterKeys { it.star }.values.sumOf { g -> minOf(1.0, g.sumOf { it.occ }.toDouble() / n) }
    }

    /** One draw of the per-run HP multiplier (FORMAT-mobs 4): `values[i]` with probability `counts[i] / sum(counts)`. */
    fun drawHpScale(table: HpScaleTable, rng: Random): Double {
        val total = table.counts.sum()
        if (total <= 0) return 1.0
        var pick = rng.nextInt(total)
        for (i in table.values.indices) { pick -= table.counts[i]; if (pick < 0) return table.values[i] }
        return table.values.last()
    }

    /**
     * One prefix-word draw for a starred clear mob (FORMAT-mobs 3.6): from [override] (an archetype's own weights, word to weight) if
     * it has any, else the rows' `weight[floor]`, reusing F7 where the floor's weight is null. Returns the chosen row; the row
     * with id "" is "no prefix".
     */
    fun rollModifier(modifiers: List<MobModifier>, floor: String, rng: Random, override: Map<String, Double>? = null): MobModifier? {
        if (modifiers.isEmpty()) return null
        val weights = modifiers.map { m ->
            if (override != null) override[m.id] ?: 0.0 else (m.weight[floor] ?: m.weight["F7"] ?: 0.0)
        }
        val total = weights.sum()
        if (total <= 0.0) return null
        var pick = rng.nextDouble() * total
        for ((i, w) in weights.withIndex()) { pick -= w; if (pick < 0.0) return modifiers[i] }
        return modifiers.last()
    }

    /** The HP a mob spawns with: `round(base * scale * modifier.hpFactor)` (FORMAT-mobs 4). */
    fun spawnHp(base: Long, scale: Double, modifier: MobModifier?): Double = Math.round(base * scale * (modifier?.hpFactor ?: 1.0)).toDouble()

    /**
     * One melee hit size in percent of max HP (FORMAT-mobs 7): with probability `sum(atoms.p)` an atom (chosen by its p), otherwise a
     * draw from the quantile table, linear in log(pct) between the bracketing keys. [rng] supplies the two uniform draws.
     */
    fun sampleMelee(table: MeleeTable, rng: Random): Double = sampleMeleeAt(table, rng.nextDouble(), rng.nextDouble() * 100.0)

    /**
     * The deterministic core of [sampleMelee]: [atomRoll] in 0..1 picks an atom when it falls under the atoms' total probability;
     * otherwise [u] in 0..100 is the percentile. Test vector: quantiles {0: 0.0, 50: 2.0, 100: 120.0} and u = 75 give 15.49.
     */
    fun sampleMeleeAt(table: MeleeTable, atomRoll: Double, u: Double): Double {
        var edge = 0.0
        for (a in table.atoms) { edge += a.p; if (atomRoll < edge) return a.value }
        val q = table.quantiles
        if (q.isEmpty()) return 0.0
        val uu = u.coerceIn(q.firstKey(), q.lastKey())
        val lo = q.headMap(uu + 1e-12).lastKey()
        val hi = q.tailMap(uu).firstKey()
        val v0 = maxOf(q.getValue(lo), 1e-3); val v1 = maxOf(q.getValue(hi), 1e-3)
        if (hi == lo) return q.getValue(lo)
        val t = (uu - lo) / (hi - lo)
        return exp(ln(v0) + (ln(v1) - ln(v0)) * t)
    }

    /**
     * A projectile hit (arrow, skull) in percent from the three recorded percentiles: piecewise linear in log between p10, p50 and
     * p90, falling to a third of p10 at the bottom and rising to 1.3 times p90 at the top (the tails are not measured).
     */
    fun sampleHit(h: HitQuantiles, rng: Random): Double = sampleHitAt(h, rng.nextDouble() * 100.0)

    fun sampleHitAt(h: HitQuantiles, u: Double): Double {
        val pts = listOf(0.0 to maxOf(h.p10 / 3.0, 1e-3), 10.0 to maxOf(h.p10, 1e-3), 50.0 to maxOf(h.p50, 1e-3), 90.0 to maxOf(h.p90, 1e-3), 100.0 to maxOf(h.p90 * 1.3, 1e-3))
        val uu = u.coerceIn(0.0, 100.0)
        for (i in 1 until pts.size) {
            val (u1, v1) = pts[i]; val (u0, v0) = pts[i - 1]
            if (uu <= u1) { val t = if (u1 == u0) 0.0 else (uu - u0) / (u1 - u0); return exp(ln(v0) + (ln(v1) - ln(v0)) * t) }
        }
        return pts.last().second
    }

    /** A version-2 UUID, as Hypixel gives its fake players: the third group starts with `2`, which is what Odin and Blade Addons test (`uuid.version() == 2`). */
    fun uuidV2(rng: Random): UUID {
        val msb = (rng.nextLong() and -0xF001L) or 0x2000L
        val lsb = (rng.nextLong() and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE
        return UUID(msb, lsb)
    }

    /** An ordinary random-style UUID (version 4) from [rng], so a rolled mob keeps its id across a re-spawn and a savepoint. */
    fun uuidV4(rng: Random): UUID {
        val msb = (rng.nextLong() and -0xF001L) or 0x4000L
        val lsb = (rng.nextLong() and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE
        return UUID(msb, lsb)
    }

    private const val NAME_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** The profile name of a fake player: ten random characters from `[a-z0-9]` (FORMAT-mobs 3.2). */
    fun profileName(rng: Random): String = buildString { repeat(10) { append(NAME_CHARS[rng.nextInt(NAME_CHARS.length)]) } }
}
