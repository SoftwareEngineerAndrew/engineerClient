package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.pack.mobs.MobArchetype
import com.engineerclient.p3sim.pack.mobs.MobModifier
import com.engineerclient.p3sim.pack.mobs.MobPack
import com.engineerclient.p3sim.pack.mobs.RoomMobs
import com.engineerclient.p3sim.pack.rooms.Placement
import java.util.UUID
import kotlin.random.Random

/**
 * One mob of a dungeon, decided before any entity exists: what it is, where it stands in the world, how much HP it has. The
 * module keeps these (ids and numbers only, so a savepoint can hold them) and builds the entities from them whenever the room's
 * chunks are loaded. Pure data.
 */
class PlannedMob(
    /** The mob's UUID for good: its body wears it, and a version-2 UUID for a fake player. */
    val id: UUID,
    /** Index in the mob pack's archetype list. */
    val arch: Int,
    val star: Boolean,
    /** Must die for the room to turn white. */
    val required: Boolean,
    /** The prefix word (`Healthy`...) or "" for none. */
    val modifier: String,
    val maxHp: Double,
    /** World position of the feet. */
    val x: Double, val y: Double, val z: Double,
    val yaw: Float,
    /** Walks (chases once a player is close, or follows its patrol loop); the rest stand still. */
    val mover: Boolean,
    /** A patrol loop as world (x, z) pairs, or null. */
    val loop: List<DoubleArray>?,
)

/** What the planner needs besides the pack: values the settings (or the run config) decide. */
class PlanParams(
    val floor: String = "F7",
    /** The per-run HP multiplier already drawn (or fixed by the config). */
    val hpScale: Double = 1.0,
    val mode: SpawnMode = SpawnMode.LAYOUTS,
    val partialWeight: Double = MobRolls.PARTIAL_LAYOUT_WEIGHT,
    /** Share of static clear mobs that become movers. */
    val moverShare: Double = AiParams().moverShare,
    /** Archetypes a room roll never spawns; another module brings them in when its trigger happens (King Midas, after its crypt is blown). */
    val deferred: Set<String> = setOf("king_midas"),
    /**
     * UNMEASURED: the chance a miniboss-design room really holds its miniboss. ADR sees a starred mob in about a third of these rooms
     * (against 60 to 74 percent of ordinary rooms), and a run has about one miniboss, so half is the working value. A provoked
     * Dungeon Recorder sweep of these rooms would measure it.
     */
    val minibossChance: Double = MobPlanner.MINIBOSS_CHANCE,
)

/**
 * Turns a room's spawn table into the mobs of one visit, seeded by [rng]: the roll (layouts or cells), the minibosses of the
 * room (one, standing on the row's cell and walking its loop), the HP (base times the run's scale times the prefix word's factor)
 * and the world position through the room's [Placement]. Pure, so the star counts per room can be tested against the pack's data shape.
 */
object MobPlanner {
    /** UNMEASURED, see [PlanParams.minibossChance]. */
    const val MINIBOSS_CHANCE = 0.5

    /** Which adventurer a miniboss room holds, by archetype id: the share of 637 recorded F7 room visits (Lost 54, Angry 27, Frozen 19 percent). */
    val MINIBOSS_WEIGHTS: Map<String, Double> = linkedMapOf("lost_adventurer" to 0.54, "angry_archaeologist" to 0.27, "frozen_adventurer" to 0.19)

    /** The Shadow Assassin a clear room holds starts at 4.2M HP (164 of 215 recorded first values); the 20M the pack carries is the boss-phase one. M7 is not measured, so it keeps the pack's number. */
    const val CLEAR_ASSASSIN_HP_F7 = 4_200_000L

    fun planRoom(pack: MobPack, roomName: String, aliasOf: ((String) -> String?)?, placement: Placement, p: PlanParams, rng: Random): List<PlannedMob> {
        val rm = pack.room(roomName, aliasOf) ?: return emptyList()
        return planFrom(pack, rm, placement, p, rng)
    }

    fun planFrom(pack: MobPack, rm: RoomMobs, placement: Placement, p: PlanParams, rng: Random): List<PlannedMob> {
        var rolled = MobRolls.rollRoom(rm, p.floor, rng, p.mode, p.partialWeight).filter { pack.archetype(it.arch)?.id !in p.deferred }
        // Miniboss rooms: a room holds at most one miniboss, only some of the time, and its type is drawn per run. The recorded
        // miniboss row only says where it stands and which loop it walks.
        val rows = pack.minibossesOf(rm.name)
        var bossLoop: List<DoubleArray>? = null
        if (rows.isNotEmpty() || rm.clear == "miniboss") {
            val row = rows.firstOrNull()
            val isBoss = { r: RolledMob -> pack.archetype(r.arch)?.cls == "miniboss" }
            val firstBoss = rolled.firstOrNull(isBoss)
            if (rng.nextDouble() >= p.minibossChance) {
                // An empty miniboss room: nothing to kill, nothing required.
                rolled = emptyList()
            } else {
                val rest = rolled.filterNot(isBoss)
                val bossArch = drawMinibossArch(pack, rng, p.deferred) ?: row?.arch ?: firstBoss?.arch
                if (bossArch != null && pack.archetype(bossArch)?.id !in p.deferred) {
                    val cell = row?.cell
                    val boss = if (cell != null) RolledMob(bossArch, true, cell.x, cell.y, cell.z) else firstBoss?.copy(arch = bossArch)
                    if (boss != null) rolled = rest + boss
                    bossLoop = row?.loop?.takeIf { it.size >= 2 }?.map { l -> placement.toWorld(l.x, l.y, l.z).let { doubleArrayOf(it.x + 0.5, it.z + 0.5) } }
                }
            }
        }
        val out = ArrayList<PlannedMob>(rolled.size)
        for (r in rolled) {
            val arch = pack.archetype(r.arch) ?: continue
            val base = clearHp(arch, p.floor) ?: continue
            // Starred and unstarred clear mobs draw a prefix word at the same rate (about 45 percent of either are modified).
            val mod: MobModifier? = if (arch.cls == "clear") MobRolls.rollModifier(pack.modifiers, p.floor, rng, arch.modifierWeights)?.takeIf { it.id.isNotEmpty() } else null
            val w = placement.toWorld(r.x, r.y, r.z)
            // Minibosses get the run's HP scale like ordinary mobs (recorded Lost Adventurers: 2.6M times 1.0 to 2.5).
            val isBoss = arch.cls == "miniboss"
            val loop = if (isBoss) bossLoop else null
            val mover = loop != null || arch.movement == "teleport" || (arch.movement != "patrol" && rng.nextDouble() < p.moverShare)
            val id = if (arch.disguise) MobRolls.uuidV2(rng) else MobRolls.uuidV4(rng)
            out += PlannedMob(id, arch.index, r.star, r.star || isBoss, mod?.id.orEmpty(), MobRolls.spawnHp(base, p.hpScale, mod), w.x + 0.5, w.y.toDouble(), w.z + 0.5, SPAWN_YAW, mover, loop)
        }
        return out
    }

    /** Hypixel adds every mob facing yaw 0 (10,643 of 11,557 recorded bodies); the ones that chase turn on their own. */
    const val SPAWN_YAW = 0f

    /** The HP an archetype spawns with in a clear room: the pack's number, except the Shadow Assassin's (see [CLEAR_ASSASSIN_HP_F7]). */
    fun clearHp(arch: MobArchetype, floor: String): Long? =
        if (arch.movement == "teleport" && floor == "F7") CLEAR_ASSASSIN_HP_F7 else arch.hpFor(floor)

    /** The index of the miniboss archetype this run's room holds, drawn by [MINIBOSS_WEIGHTS] among the ones the pack has; null when the pack has none of them. */
    fun drawMinibossArch(pack: MobPack, rng: Random, deferred: Set<String>): Int? {
        val have = MINIBOSS_WEIGHTS.filter { (id, _) -> id !in deferred && pack.archetypeById(id) != null }
        if (have.isEmpty()) return null
        var pick = rng.nextDouble() * have.values.sum()
        for ((id, w) in have) { pick -= w; if (pick < 0.0) return pack.archetypeById(id)!!.index }
        return pack.archetypeById(have.keys.last())!!.index
    }

    /** The archetype's AI kind and chase speed, for the module. */
    fun aiKind(arch: MobArchetype, teleportOn: Boolean = false): AiKind = MobAi.kindOf(arch.id, arch.entity, arch.movement, teleportOn)
}
