package com.engineerclient.p3sim.clear.damage

import kotlin.math.ceil
import kotlin.random.Random

/**
 * The Hyperion Implosion against mobs, as pure rules (no world): which mobs a cast reaches, how hard it hits, how many casts a mob
 * needs. Measured on Hypixel (player-kit scout, 23,829 Implosion lines, 11,518 mob health bars):
 *  - the cast reaches every mob whose hitbox touches a box around the caster's eye, 6 blocks each way sideways, 6 down and 7 up,
 *    through walls, the same damage to each, no falloff and no sharing between them;
 *  - F7: one mob takes a median 26.5 million per cast, but how much depends on the kind of mob (a Super Tank Zombie 1.5 million,
 *    a Zombie Knight 70 million) and on the cast (the same kind took 25 to 67 million). No ordinary mob has more than 8.8 million
 *    health, so one cast kills; minibosses and the Shadow Assassin take a capped hit and need two;
 *  - M7: about 56.6 million per cast against 40 to 310 million (720 million seen), so a mob takes 1 to 13 casts.
 */
object ImplosionRules {
    /** Sideways reach from the eye to the nearest part of a mob's hitbox. */
    const val REACH = 6.0
    const val REACH_UP = 7.0
    const val REACH_DOWN = 6.0

    /** What a cast does per mob on a floor, before the player's own multiplier. */
    class Profile(val floor: String, val median: Double, val byKind: Boolean = false) {
        /** One cast's damage per mob when every mob takes the same: the median with the cast-to-cast spread seen in one recorded run (0.9 to 1.15 times). */
        fun roll(random: Random, multiplier: Double = 1.0): Double = median * multiplier * (0.9 + random.nextDouble() * 0.25)

        /** The cast's own base (player buffs differ cast to cast: 0.7 to 1.3 times the median), drawn once per cast. */
        fun castBase(random: Random, multiplier: Double = 1.0): Double =
            if (byKind) median * multiplier * (0.7 + random.nextDouble() * 0.6) else roll(random, multiplier)

        /**
         * What one mob of [kind] takes from a cast whose base is [base]. F7 scales the base by the kind's factor with a small
         * per-mob spread; a miniboss (and the Shadow Assassin) takes at most 0.75 times its unscaled health ([maxHp] divided by
         * [hpScale]), which is why they need two casts. M7 was not measured per kind and stays flat.
         */
        fun perMob(kind: String, miniboss: Boolean, maxHp: Double, hpScale: Double, base: Double, random: Random): Double {
            if (!byKind) return base
            val raw = base * factor(kind) * (0.97 + random.nextDouble() * 0.06)
            if (!miniboss && kind != "shadow_assassin") return raw
            val unscaled = if (hpScale > 0.0) maxHp / hpScale else maxHp
            return minOf(raw, 0.75 * unscaled * (0.95 + random.nextDouble() * 0.10))
        }
    }

    private val F7 = Profile("F7", 36_000_000.0, byKind = true)
    private val M7 = Profile("M7", 56_600_000.0)

    /**
     * How hard a cast hits a kind of mob, relative to a Fels (25.4 million at the median cast). Approximate: from single-kill casts,
     * a handful each. A kind not listed takes 1.0.
     */
    private val FACTORS = mapOf(
        "super_tank_zombie" to 0.06, "angry_archaeologist" to 0.015, "lost_adventurer" to 0.09, "frozen_adventurer" to 0.18,
        "withermancer" to 0.37, "crypt_undead" to 0.54, "mimic" to 0.62, "prince" to 0.6, "crypt_souleater" to 1.14,
        "wither_miner" to 1.4, "skeleton_master" to 1.9, "crypt_lurker" to 2.6, "zombie_knight" to 2.8, "zombie_soldier" to 1.45,
        "sniper" to 1.3,
    )

    /** The cast-damage factor of an archetype id (see [FACTORS]). */
    fun factor(kind: String): Double = FACTORS[kind] ?: 1.0

    /** The profile of [floor] (`F7`, `M7`); other floors use F7's numbers (nothing else was measured). */
    fun profile(floor: String): Profile = if (floor.startsWith("M")) M7 else F7

    /** How many casts of at least [perCast] damage it takes to kill [hp]. A mob at 0 needs none. */
    fun castsNeeded(hp: Double, perCast: Double): Int = if (hp <= 0.0) 0 else if (perCast <= 0.0) Int.MAX_VALUE else ceil(hp / perCast).toInt()

    /** A mob's hitbox: feet position, width, height (blocks). */
    class Hitbox(val x: Double, val y: Double, val z: Double, val width: Double, val height: Double) {
        val minX get() = x - width / 2; val maxX get() = x + width / 2
        val minZ get() = z - width / 2; val maxZ get() = z + width / 2
        val minY get() = y; val maxY get() = y + height
    }

    /**
     * The body size of an archetype: a skeleton 0.6 x 1.99, an enderman-sized Fels 0.6 x 2.9, a wither skeleton 0.7 x 2.4, everything
     * else a zombie 0.6 x 1.95. Only used when the body entity is not there to ask (a mob in an unloaded chunk).
     */
    fun size(kind: String): Pair<Double, Double> = when {
        kind == "fels" -> 0.6 to 2.9
        kind.startsWith("withermancer") || kind.contains("wither_skeleton") -> 0.7 to 2.4
        kind.contains("skeleton") || kind == "super_archer" || kind == "sniper" -> 0.6 to 1.99
        else -> 0.6 to 1.95
    }

    /** The box of a cast from eye position ([ex],[ey],[ez]): `[x0,y0,z0,x1,y1,z1]`. */
    fun castBox(ex: Double, ey: Double, ez: Double): DoubleArray =
        doubleArrayOf(ex - REACH, ey - REACH_DOWN, ez - REACH, ex + REACH, ey + REACH_UP, ez + REACH)

    /** True when [h] touches [box] (`[x0,y0,z0,x1,y1,z1]`) on all three axes. */
    fun touches(h: Hitbox, box: DoubleArray): Boolean =
        h.maxX >= box[0] && h.minX <= box[3] && h.maxY >= box[1] && h.minY <= box[4] && h.maxZ >= box[2] && h.minZ <= box[5]

    /** One mob the selection looks at. */
    class Candidate(val id: java.util.UUID, val hitbox: Hitbox, val alive: Boolean, val untargetable: Boolean)

    /** The ids a cast in [box] reaches: alive, touching, and not untargetable (a sleeping Fels cannot be hit). */
    fun select(box: DoubleArray, mobs: Collection<Candidate>): List<java.util.UUID> =
        mobs.filter { it.alive && !it.untargetable && touches(it.hitbox, box) }.map { it.id }

    /** Hypixel's chat line for a cast: `Your Implosion hit N enemy/enemies for X damage.`, X with a thousands comma and one decimal, no ".0". */
    fun line(count: Int, total: Double): String {
        val shown = "%,.1f".format(java.util.Locale.ROOT, total).removeSuffix(".0")
        return "§7Your Implosion hit §r§c$count §r§7${if (count == 1) "enemy" else "enemies"} for §r§c$shown §r§7damage."
    }
}
