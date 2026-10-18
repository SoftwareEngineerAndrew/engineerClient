package com.engineerclient.p3sim.clear.mobs

import kotlin.math.sqrt

/**
 * The numbers AI v0 runs on. Only [moverShare] and the Fels wake sphere come from recordings; everything else is a documented
 * placeholder (FORMAT-mobs 9: aggro radius, chase speed, attack cadence are NOT measured), so each is a named value the player can
 * change as a setting ("Mobs ..."). Speeds are blocks per tick.
 */
data class AiParams(
    /** Share of mobs that ever walk toward a player. Recordings: 33 to 45 percent of mobs seen 2 to 12 blocks from the player move (the ADR figure of a quarter counts bodies that died first). */
    val moverShare: Double = 0.4,
    /** UNMEASURED: a player closer than this wakes a mover's chase. Recorded skeleton draw edges sit around 8 to 20 blocks, zombies at melee reach. */
    val aggroRadius: Double = 12.0,
    /** UNMEASURED: a chasing mob gives up when the nearest player is farther than this. */
    val loseRadius: Double = 28.0,
    /** UNMEASURED: how close a melee hit must be (blocks, centre to centre). */
    val meleeReach: Double = 2.3,
    /** UNMEASURED: ticks between melee swings of one mob. */
    val meleeCadence: Int = 20,
    /** UNMEASURED: an archer or Withermancer shoots from at most this far. */
    val rangedRange: Double = 16.0,
    /** UNMEASURED: ticks between shots. */
    val rangedCadence: Int = 40,
    /** UNMEASURED: a mob that never moves still hits a player standing this close (0 turns static attacks off). */
    val staticReach: Double = 2.3,
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
    /** Server ticks until the next swing or shot. */
    var cooldown = 0
    var sinceTeleport = 0
    /** Next point of a patrol loop. */
    var loopIndex = 0
}

/** Where the nearest target stands, and how far (horizontal), or null when nobody is near. */
class AiTarget(val x: Double, val z: Double, val dist: Double)

/**
 * AI v0, pure: from the mob's position, state and the nearest target, the action for this tick. A mob that is not a mover
 * stands still unless a player is inside [AiParams.staticReach] (then it swings). A mover starts a chase when a player comes within
 * the aggro radius and stops past the lose radius. Melee kinds walk up and swing; ranged kinds stop at range and shoot; a
 * teleporting kind (the Shadow Assassin) appears behind its target every so often, then swings. A mob with a patrol [loop]
 * that is not chasing walks it. Sleepers are handled by the caller (an asleep Fels never reaches this).
 */
object MobAi {
    fun step(kind: AiKind, mover: Boolean, x: Double, z: Double, speed: Double, target: AiTarget?, state: AiState, loop: List<DoubleArray>?, p: AiParams): AiAction {
        if (state.cooldown > 0) state.cooldown--
        state.sinceTeleport++
        if (target == null) {
            state.chasing = false
            return patrol(x, z, state, loop, p, mover)
        }
        if (!mover && kind != AiKind.TELEPORT) {
            if (p.staticReach > 0.0 && target.dist <= p.staticReach) return attack(kind, target, state, p)
            return AiAction.Idle
        }
        if (state.chasing) { if (target.dist > p.loseRadius) state.chasing = false } else if (target.dist <= p.aggroRadius) state.chasing = true
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
            AiKind.RANGED -> if (target.dist <= p.rangedRange) {
                if (state.cooldown == 0) { state.cooldown = p.rangedCadence; AiAction.Shoot } else AiAction.Idle
            } else AiAction.MoveTo(target.x, target.z, speed)
            AiKind.MELEE -> if (target.dist <= p.meleeReach) attack(kind, target, state, p) else AiAction.MoveTo(target.x, target.z, speed)
        }
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
