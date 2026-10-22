package com.engineerclient.p3sim.clear.blood.camp

import kotlin.random.Random

/** What a wall head stands for: one giant, a handful of revived bosses, the 17 standard mobs, the Wandering Soul, and heads that make nothing. */
enum class WallClass { GIANT, BOSS, STANDARD, WANDERING_SOUL, DECOR }

/** One wall head of the blood room: [slot] is the host's head index, [stand] where its stand hangs (world), [cls] and [mobName] what it makes. */
class WallStation(val slot: Int, val stand: Vec3, val cls: WallClass, val mobName: String)

/** One mob the Watcher is about to put into the room. [landTick] is the tick the real mob is "added" (a thrown head lands); the mob spawns on [spawnTick]. */
class Landing(val station: WallStation, val initial: Boolean, val throwTick: Int, val spawnTick: Int, val landTick: Int)

/**
 * What the wall of the blood room shows in one run, and which heads the Watcher throws in which order. Pure.
 *
 * A complete real wall has 30 heads (the same on every complete wall measured): one giant, the four revived bosses once each, each of
 * the 17 standard mobs once, one Wandering Soul and 7 heads that make nothing. The Watcher throws a giant, a boss and two standard
 * mobs first; every later head is drawn uniformly from the standard heads that are left. Which head sits on which stand is redrawn
 * every camp. The Wandering Soul's head is thrown in about 3% of camps, as an extra leg whose mob is never counted.
 */
object WallPlan {
    /** The revived bosses a wall can hold, once each. */
    val WALL_BOSSES = listOf("Livid", "Spirit Bear", "Bonzo", "Scarf")

    /** The 17 standard mobs of the wall. */
    val STANDARD = listOf(
        "Freak", "Reaper", "Tear", "Leech", "Mr. Dead", "Putrid", "Psycho", "Ooze", "Mute",
        "Vader", "Parasite", "Walker", "Revoker", "Cannibal", "Skull", "Frost", "Flamer",
    )

    /** Heads of a full wall that make nothing. */
    const val DECOR_PER_30 = 7

    /** Measured: 23 of about 700 complete camps threw the Wandering Soul head. */
    const val WS_THROW_CHANCE = 0.033

    /** The name the Wandering Soul head's mob carries (also its tag text). */
    const val WS_NAME = "Wandering Soul"

    /**
     * Gives every slot a class and a mob name. [stands] are the stand positions by slot index. A 30-head wall gets exactly
     * 1 giant, 4 bosses, 17 standard, 1 Wandering Soul and 7 decor; a smaller wall drops decor first, then the soul.
     */
    fun assign(stands: List<Vec3>, rng: Random): List<WallStation> {
        val n = stands.size
        if (n == 0) return emptyList()
        val order = (0 until n).shuffled(rng)
        val giants = 1
        val bosses = minOf(WALL_BOSSES.size, if (n > 2) maxOf(1, n - 2) else 0)
        val ws = if (n >= giants + bosses + STANDARD.size + 1) 1 else 0
        val standard = minOf(STANDARD.size, maxOf(0, n - giants - bosses - ws))
        val names = STANDARD.shuffled(rng)
        val bossNames = WALL_BOSSES.shuffled(rng)
        val out = arrayOfNulls<WallStation>(n)
        var i = 0
        repeat(giants) { val s = order[i++]; out[s] = WallStation(s, stands[s], WallClass.GIANT, "Giant") }
        repeat(bosses) { k -> val s = order[i++]; out[s] = WallStation(s, stands[s], WallClass.BOSS, bossNames[k]) }
        repeat(standard) { k -> val s = order[i++]; out[s] = WallStation(s, stands[s], WallClass.STANDARD, names[k]) }
        repeat(ws) { val s = order[i++]; out[s] = WallStation(s, stands[s], WallClass.WANDERING_SOUL, WS_NAME) }
        while (i < n) { val s = order[i++]; out[s] = WallStation(s, stands[s], WallClass.DECOR, "") }
        return out.map { it!! }
    }

    /**
     * The stations in throw order: giant, boss, two standard, then [later] more standard ones, each drawn uniformly from those
     * left. If the wall is too small it falls back to boss heads, then decor heads, so the order is always as long as the wall
     * allows (a wall with fewer than `4 + later` heads throws what it has).
     */
    fun throwOrder(wall: List<WallStation>, rng: Random, later: Int, wsChance: Double = 0.0): List<WallStation> {
        val left = wall.toMutableList()
        val out = ArrayList<WallStation>()
        fun take(vararg classes: WallClass): WallStation? {
            for (c in classes) {
                val pool = left.filter { it.cls == c }
                if (pool.isNotEmpty()) return pool[rng.nextInt(pool.size)].also { left.remove(it) }
            }
            return null
        }
        take(WallClass.GIANT, WallClass.BOSS, WallClass.STANDARD, WallClass.DECOR)?.let { out += it }
        take(WallClass.BOSS, WallClass.STANDARD, WallClass.DECOR)?.let { out += it }
        repeat(2) { take(WallClass.STANDARD, WallClass.BOSS, WallClass.DECOR)?.let { out += it } }
        repeat(later) { take(WallClass.STANDARD, WallClass.BOSS, WallClass.DECOR)?.let { out += it } }
        // The Wandering Soul is an extra, uncounted leg somewhere among the later throws.
        if (wsChance > 0.0 && rng.nextDouble() < wsChance) {
            left.firstOrNull { it.cls == WallClass.WANDERING_SOUL }?.let { out.add(rng.nextInt(minOf(4, out.size), out.size + 1), it) }
        }
        return out
    }

    /**
     * Where a thrown head ends: from its [stand] in a straight line toward [target] (the room centre at head height), [length]
     * blocks long, in 3D. The real flight is 16.0 blocks for the first four heads and 12.0 for the later ones, and the line is
     * exact (perpendicular error 0.00 +-0.02 in 1,310 heads); the end is not clamped, so an initial head from a near wall
     * lands past the centre.
     */
    fun headEnd(stand: Vec3, target: Vec3, length: Double): Vec3 {
        val d = target - stand
        val l = d.length()
        if (l < 1e-6) return stand
        return stand + d * (length / l)
    }
}
