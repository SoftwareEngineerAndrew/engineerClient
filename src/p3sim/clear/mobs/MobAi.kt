package com.engineerclient.p3sim.clear.mobs

import kotlin.math.sqrt

/**
 * The numbers the mob AI runs on (v1, 2026-10-19). Hypixel runs the 1.8 server, whose mobs pick a target with a 1 in 10 chance a tick among the
 * players they can SEE within their follow range, keep it while it stays within that range and was seen in the last 60 ticks, hit from
 * sqrt((2 x width)^2 + target width) away every 20 ticks, and (skeletons) shoot from at most 15 blocks every 20 + 40 x distance / 15 ticks.
 * The recordings agree (ADR, 484 runs, clear phase, research/mobai.js): a mover's first step comes when the nearest player is a median 13
 * blocks away and p90 32 to 43 for every kind (n=525: the room's walls hide players until they are close); mob shots (2,296 arrows and wither
 * skulls) leave from a median 5.9 blocks, p90 13.5; a Withermancer's first shot comes a median 31 ticks after a player is within 15 blocks.
 * Speeds are blocks per tick. Each value is a setting ("Mobs ...").
 *
 * v2 (Dungeon Recorder, 223 F7 runs, 9,746 clear mobs, research dr/walks*.js): Hypixel's clear mobs do not chase. Three in four never take a
 * walk of 3 blocks while a player is near; a walk is short (median 6 blocks, p90 12 to 25) and stops, at about 0.85 of the mob's measured
 * pace, and walks come more often the nearer a player is (per mob-minute: 7.0 within 6 blocks, 5.9 at 6 to 12, 3.9 at 12 to 20, 2.1 at 20 to
 * 32, 1.35 beyond). About a third of the walks within 20 blocks head at the nearest player (51 to 56 percent point within 45 degrees of it,
 * against 25 for a random heading), the rest anywhere. So by default no mob is a chaser ([moverShare] 0) and every mob strolls ([strolls]).
 */
data class AiParams(
    /** Share of mobs that chase a player they see until they reach it (the sim's v1 AI). 0 by default: the Dungeon Recorder shows no chases, only short walks ([strolls]). */
    val moverShare: Double = 0.0,
    /** Whether mobs that do not chase take the recorded short walks (see the class doc). */
    val strolls: Boolean = true,
    /** Walks per mob-minute by the nearest player's distance: within 6, 12, 20 and 32 blocks, and beyond (Dungeon Recorder). */
    val strollRates: DoubleArray = doubleArrayOf(7.0, 5.9, 3.9, 2.1, 1.35),
    /** Share of the walks in each of those bands that head at the nearest player it can see (the rest go anywhere). */
    val strollAimed: DoubleArray = doubleArrayOf(0.31, 0.41, 0.35, 0.2, 0.11),
    /** A walk's pace against the mob's chase speed (recorded walks: median 0.85 of the q75 pace). */
    val strollPace: Double = 0.85,
    /** A seen player closer than this can become a mover's target (1.8's follow range; recorded first steps reach p90 32 to 43). */
    val aggroRadius: Double = 35.0,
    /** A chasing mob gives up when its target is farther than this (the follow range), or unseen for [unseenMemory] ticks. */
    val loseRadius: Double = 35.0,
    /** How close a melee hit must be, blocks centre to centre: 1.8's sqrt((2 x 0.6)^2 + 0.6) for a 0.6-wide body. Not recorded (zombies' swings are not in the recordings). */
    val meleeReach: Double = 1.43,
    /** Ticks between melee swings of one mob (1.8's attack timer). */
    val meleeCadence: Int = 20,
    /** An archer or Withermancer shoots from at most this far (1.8: 15; recorded shots p90 13.5). */
    val rangedRange: Double = 15.0,
    /** Ticks between shots at the edge of [rangedRange]; at point blank [rangedMinCadence] (1.8: 20 + 40 x distance / range, the distance share at least 0.1). */
    val rangedCadence: Int = 60,
    val rangedMinCadence: Int = 20,
    /** A mob that never moves still hits a player standing this close (0 turns static attacks off). */
    val staticReach: Double = 1.43,
    /** Chance a tick that a mob without a target looks for one (1.8: 1 in 10). */
    val searchChance: Double = 0.1,
    /** Ticks a chasing mob keeps a target it cannot see (1.8: 60). */
    val unseenMemory: Int = 60,
    /** Chase speeds of a body type, for archetypes [MobAi.ARCHETYPE_SPEEDS] has no number for (MEASURED: q75 of 6-tick windows approaching the player in the traversal corpus). */
    val zombieSpeed: Double = 0.28,
    val skeletonSpeed: Double = 0.137,
    val witherSkeletonSpeed: Double = 0.27,
    /** Fels (q75 0.32) and any other body. */
    val otherSpeed: Double = 0.32,
    /** Ticks between a Shadow Assassin's teleports behind its target; 0 (the default) means it walks. Recordings: 2 of 65 clear-phase assassins ever jumped more than 3 blocks. */
    val assassinTeleportEvery: Int = 0,
    /** Blocks a patrolling miniboss walks per tick before it sees anyone (recorded adventurers move at a median of 0.25 to 0.29 before their first hit). */
    val patrolSpeed: Double = 0.25,
    /** Speed factor of a Speedy mob (PROVISIONAL, between the measured 1.4 and 2.0). */
    val speedyFactor: Double = 1.5,
    /** How close to a loop point counts as reached. */
    val patrolArrive: Double = 1.0,
)

/** How a mob fights, from its archetype. */
enum class AiKind { MELEE, RANGED, TELEPORT }

/** One thing a mob does this tick. */
sealed interface AiAction {
    data object Idle : AiAction
    /** Walk toward (x, z) at [speed] blocks per tick; y is the mob's own. */
    data class MoveTo(val x: Double, val z: Double, val speed: Double) : AiAction
    /** Swing at the target now. */
    data object Melee : AiAction
    /** Shoot at the target now. */
    data object Shoot : AiAction
    /** Appear at (x, z) beside the target. */
    data class Teleport(val x: Double, val z: Double) : AiAction
}

/** What one mob remembers between ticks. */
class AiState {
    var chasing = false
    /** Ticks the target has been out of sight while chasing. */
    var unseen = 0
    /** A shooter has drawn: its first shot comes when [cooldown] runs out (1.8 waits one interval before the first). */
    var drawn = false
    /** Ticks in a row a chasing shooter has seen its target (1.8 EntityAIArrowAttack: it keeps walking at it until 20, even in range). */
    var seeTime = 0
    /** Server ticks until the next swing or shot. */
    var cooldown = 0
    var sinceTeleport = 0
    /** Next point of a patrol loop. */
    var loopIndex = 0
    /** The point a stroll walks to, and the ticks it has left (0: not strolling). */
    var strollX = 0.0
    var strollZ = 0.0
    var strollLeft = 0
}

/** Where the nearest target stands, how far (horizontal), and whether the mob can see it (a clear line from eye to eye). */
class AiTarget(val x: Double, val z: Double, val dist: Double, val seen: Boolean = true)

/**
 * AI v0, pure: from the mob's position, state and the nearest target, the action for this tick. A mob that is not a mover
 * stands still unless a player is inside [AiParams.staticReach] (then it swings). A mover starts a chase when a player comes within
 * the aggro radius and stops past the lose radius. Melee kinds walk up and swing; ranged kinds stop at range and shoot; a
 * teleporting kind (the Shadow Assassin) appears behind its target every so often, then swings. A mob with a patrol [loop]
 * that is not chasing walks it. Sleepers are handled by the caller (an asleep Fels never reaches this).
 */
object MobAi {
    /** Ticks a chasing shooter must have seen its target before it stops to shoot (1.8 EntityAIArrowAttack). */
    const val SEE_BEFORE_STOP = 20

    /** [search]: this tick the mob looks for a target (the caller rolls [AiParams.searchChance]). [rng] draws the strolls (none without it). */
    fun step(kind: AiKind, mover: Boolean, x: Double, z: Double, speed: Double, target: AiTarget?, state: AiState, loop: List<DoubleArray>?, p: AiParams, search: Boolean = true, rng: kotlin.random.Random? = null): AiAction {
        if (state.cooldown > 0) state.cooldown--
        state.sinceTeleport++
        if (target == null) {
            state.chasing = false; state.drawn = false; state.seeTime = 0
            return patrol(x, z, state, loop, p, mover).let { if (it == AiAction.Idle && loop.isNullOrEmpty()) stroll(x, z, speed, null, state, p, rng) else it }
        }
        if (!mover && kind != AiKind.TELEPORT) {
            // A shooter that never chases still shoots what it sees in range; a swing or a shot comes before a walk.
            if (kind == AiKind.RANGED && target.seen && target.dist <= p.rangedRange) {
                val act = shoot(target, state, p)
                if (act != AiAction.Idle) { state.strollLeft = 0; return act }
            } else if (kind == AiKind.RANGED) state.drawn = false
            if (kind != AiKind.RANGED && p.staticReach > 0.0 && target.dist <= p.staticReach) return attack(kind, target, state, p)
            if (!loop.isNullOrEmpty()) return patrol(x, z, state, loop, p, mover)
            return stroll(x, z, speed, target, state, p, rng)
        }
        if (state.chasing) {
            state.unseen = if (target.seen) 0 else state.unseen + 1
            if (target.dist > p.loseRadius || state.unseen > p.unseenMemory) { state.chasing = false; state.unseen = 0; state.drawn = false; state.seeTime = 0 }
        } else if (search && target.seen && target.dist <= p.aggroRadius) state.chasing = true
        if (!state.chasing) return patrol(x, z, state, loop, p, mover)
        return when (kind) {
            AiKind.TELEPORT -> {
                if (p.assassinTeleportEvery > 0 && state.sinceTeleport >= p.assassinTeleportEvery && target.dist > p.meleeReach) {
                    state.sinceTeleport = 0
                    // Behind the target is behind the line from the mob to it: one block past the target, away from the mob.
                    val dx = target.x - x; val dz = target.z - z
                    val len = maxOf(sqrt(dx * dx + dz * dz), 1e-6)
                    AiAction.Teleport(target.x + dx / len * 1.5, target.z + dz / len * 1.5)
                } else if (target.dist <= p.meleeReach) attack(kind, target, state, p) else AiAction.MoveTo(target.x, target.z, speed)
            }
            AiKind.RANGED -> {
                state.seeTime = if (target.seen) state.seeTime + 1 else 0
                if (target.dist <= p.rangedRange && target.seen) {
                    // 1.8 draws as it comes in range but walks on until it has seen the target for [SEE_BEFORE_STOP] ticks (the first shot is later anyway).
                    val act = shoot(target, state, p)
                    if (state.seeTime < SEE_BEFORE_STOP && act != AiAction.Shoot) AiAction.MoveTo(target.x, target.z, speed) else act
                } else { state.drawn = false; AiAction.MoveTo(target.x, target.z, speed) }
            }
            AiKind.MELEE -> if (target.dist <= p.meleeReach) attack(kind, target, state, p) else AiAction.MoveTo(target.x, target.z, speed)
        }
    }

    /** The band of [AiParams.strollRates] a player [dist] blocks away falls in. */
    fun strollBand(dist: Double): Int = when { dist < 6 -> 0; dist < 12 -> 1; dist < 20 -> 2; dist < 32 -> 3; else -> 4 }

    /**
     * A short walk now and then (the Dungeon Recorder's walks): a walk under way goes on until the mob is within half a block of its end
     * or its time is up; otherwise one starts with the chance its band's rate gives a tick. It heads at the nearest seen player (stopping
     * a block short) with its band's aimed share, else anywhere; its length is 3 blocks plus an exponential of mean 4 (median 5.8, p90 12),
     * at [AiParams.strollPace] of the chase [speed].
     */
    private fun stroll(x: Double, z: Double, speed: Double, target: AiTarget?, state: AiState, p: AiParams, rng: kotlin.random.Random?): AiAction {
        val pace = speed * p.strollPace
        if (state.strollLeft > 0) {
            state.strollLeft--
            val dx = state.strollX - x; val dz = state.strollZ - z
            if (dx * dx + dz * dz > 0.25 && state.strollLeft > 0) return AiAction.MoveTo(state.strollX, state.strollZ, pace)
            state.strollLeft = 0
            return AiAction.Idle
        }
        if (!p.strolls || rng == null || pace <= 0.0) return AiAction.Idle
        val band = strollBand(target?.dist ?: Double.MAX_VALUE)
        if (rng.nextDouble() >= p.strollRates[band] / 1200.0) return AiAction.Idle
        var len = minOf(25.0, 3.0 - 4.0 * Math.log(1.0 - rng.nextDouble()))
        val heading: Double
        if (target != null && target.seen && rng.nextDouble() < p.strollAimed[band]) {
            heading = Math.atan2(target.z - z, target.x - x)
            len = minOf(len, target.dist - 1.0)
            if (len < 1.0) return AiAction.Idle
        } else heading = rng.nextDouble() * 2 * Math.PI
        state.strollX = x + Math.cos(heading) * len
        state.strollZ = z + Math.sin(heading) * len
        // Time for the walk and a little more (a body that cannot get there gives up).
        state.strollLeft = (len / pace).toInt() + 40
        return AiAction.MoveTo(state.strollX, state.strollZ, pace)
    }

    /** Ticks between a shooter's shots at [dist] (1.8's skeleton). */
    fun shotInterval(dist: Double, p: AiParams): Int {
        val f = (dist / p.rangedRange).coerceIn(0.1, 1.0)
        return (p.rangedMinCadence + (p.rangedCadence - p.rangedMinCadence) * f).toInt()
    }

    private fun shoot(target: AiTarget, state: AiState, p: AiParams): AiAction {
        if (!state.drawn) { state.drawn = true; state.cooldown = shotInterval(target.dist, p); return AiAction.Idle }
        if (state.cooldown > 0) return AiAction.Idle
        state.cooldown = shotInterval(target.dist, p)
        return AiAction.Shoot
    }

    private fun attack(kind: AiKind, target: AiTarget, state: AiState, p: AiParams): AiAction {
        if (state.cooldown > 0) return AiAction.Idle
        state.cooldown = p.meleeCadence
        return AiAction.Melee
    }

    private fun patrol(x: Double, z: Double, state: AiState, loop: List<DoubleArray>?, p: AiParams, mover: Boolean): AiAction {
        if (loop.isNullOrEmpty()) return AiAction.Idle
        var i = state.loopIndex.coerceIn(0, loop.size - 1)
        val dx = loop[i][0] - x; val dz = loop[i][1] - z
        if (dx * dx + dz * dz <= p.patrolArrive * p.patrolArrive) { i = (i + 1) % loop.size; state.loopIndex = i }
        return AiAction.MoveTo(loop[i][0], loop[i][1], p.patrolSpeed)
    }

    /** The AI kind of an archetype: skeletons, the Withermancer and the Sniper shoot; the Shadow Assassin walks and swings unless [AiParams.assassinTeleportEvery] turns its teleport on; the rest swing. */
    fun kindOf(archId: String, entity: String, movement: String, teleportOn: Boolean = false): AiKind = when {
        movement == "teleport" && teleportOn -> AiKind.TELEPORT
        archId in RANGED_IDS || entity == "minecraft:skeleton" || entity == "minecraft:wither_skeleton" -> AiKind.RANGED
        else -> AiKind.MELEE
    }

    private val RANGED_IDS = setOf("withermancer", "super_archer", "sniper", "skeleton_master", "skeleton_soldier", "skeleton_lord")

    /** Cruise speed (blocks per tick, q75 of approaching windows) per archetype id; archetypes not listed use their body type's speed. */
    val ARCHETYPE_SPEEDS: Map<String, Double> = mapOf(
        "skeleton_soldier" to 0.137, "skeleton_master" to 0.137, "skeleton_lord" to 0.137, "super_archer" to 0.137,
        "crypt_lurker" to 0.183, "super_tank_zombie" to 0.168,
        "zombie_soldier" to 0.278, "zombie_knight" to 0.28, "zombie_commander" to 0.19,
        "crypt_dreadlord" to 0.172, "crypt_souleater" to 0.123, "withermancer" to 0.27,
        "lost_adventurer" to 0.286, "angry_archaeologist" to 0.30, "frozen_adventurer" to 0.30, "fels" to 0.32,
    )

    /** The chase speed of one mob: its archetype's number (else its body's), times [AiParams.speedyFactor] for a Speedy one. */
    fun speedFor(archId: String, entity: String, speedy: Boolean, p: AiParams): Double =
        (ARCHETYPE_SPEEDS[archId] ?: (if (archId.startsWith(BloodLooks.PREFIX)) com.engineerclient.p3sim.clear.blood.camp.CampMobs.SPEED else null) ?: speedOf(entity, p)) * (if (speedy) p.speedyFactor else 1.0)

    /** The chase speed (blocks per tick) for a body type. */
    fun speedOf(entity: String, p: AiParams): Double = when (entity) {
        "minecraft:zombie" -> p.zombieSpeed
        "minecraft:skeleton" -> p.skeletonSpeed
        "minecraft:wither_skeleton" -> p.witherSkeletonSpeed
        else -> p.otherSpeed
    }
}
