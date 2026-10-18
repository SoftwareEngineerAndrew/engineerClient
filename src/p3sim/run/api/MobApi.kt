package com.engineerclient.p3sim.run.api

import com.engineerclient.p3sim.run.MobRef
import com.engineerclient.p3sim.run.Run
import java.util.UUID

/**
 * A mob as the sim tracks it, without an entity: other modules hold these, not Minecraft types. Written ONLY by the mobs
 * module; everyone else reads. [hp] is the SkyBlock-scale HP the nametag shows (up to hundreds of millions), not the vanilla
 * entity health.
 */
class MobRecord(
    /** The body entity's UUID (for a disguised mob the server body's, not the fake player's on the wire). */
    val id: UUID,
    /** Mob-pack archetype id (`crypt_dreadlord`) or a module special (`watcher`, `mimic`). */
    val kind: String,
    /** The nametag name without star, modifier or HP. */
    val name: String,
    val roomIndex: Int?,
    val starred: Boolean,
    /** Must die for the room to turn WHITE. */
    val required: Boolean,
    val miniboss: Boolean,
    val disguised: Boolean,
) {
    @Volatile var hp: Double = 0.0
    @Volatile var maxHp: Double = 0.0
    @Volatile var alive: Boolean = true
    /** Last known position (updated by the mobs module each tick for mobs that move). */
    @Volatile var x: Double = 0.0
    @Volatile var y: Double = 0.0
    @Volatile var z: Double = 0.0
    /** The body's entity id, for packets and logs; null before it spawns. */
    @Volatile var entityId: Int? = null

    fun ref() = MobRef(id, kind, roomIndex, starred, required)
}

/**
 * Asks the mobs module for one mob outside its room tables: a camp mob, the Mimic, a special. Positions are world
 * coordinates (feet).
 */
data class MobSpec(
    /** Archetype id from the mob pack, or a module special id. */
    val kind: String,
    val x: Double, val y: Double, val z: Double,
    val roomIndex: Int? = null,
    val starred: Boolean = false,
    val required: Boolean = false,
    /** HP override; null = the archetype's HP for the floor, times the run's scale. */
    val hp: Double? = null,
    /** Free-form hints the mobs module may use (`"name"`, `"modifier"`). */
    val extras: Map<String, String> = emptyMap(),
)

/**
 * The mob registry and spawner. Implemented by [com.engineerclient.p3sim.clear.mobs.MobsModule]; used by the damage module
 * (HP changes), the doors module (is a room's last required mob dead), the secrets module (bonus mobs), the blood module
 * (camp mobs) and the bots. Server thread only.
 */
interface MobService {
    /** Rolls the room's table from the mob pack and spawns its mobs (before the first player enters). Returns what spawned. */
    fun spawnRoom(run: Run, roomIndex: Int): List<MobRecord> = emptyList()

    /** Spawns one mob by spec. Null when it could not be made. */
    fun spawn(run: Run, spec: MobSpec): MobRecord? = null

    fun mob(id: UUID): MobRecord? = null
    fun inRoom(roomIndex: Int): List<MobRecord> = emptyList()
    fun all(): Collection<MobRecord> = emptyList()

    /** Mobs of the room that must still die for WHITE. */
    fun requiredAlive(roomIndex: Int): Int = 0

    /**
     * The hook the damage module uses: sets a mob's HP. The mobs module updates the nametag, posts `MobDamaged`, and when
     * [newHp] <= 0 kills it (posts `MobKilled`, removes the stand and body on the same tick, and tells the doors module's
     * key rule through [DoorService.keyForRoom]/[DoorService.dropKey]). Returns the record, or null if the mob is unknown.
     */
    fun setHp(run: Run, id: UUID, newHp: Double, by: UUID?): MobRecord? = null

    /** The record of the mob whose body has this entity id, or null. The way from a hit entity to a mob. */
    fun recordOfEntity(entityId: Int): MobRecord? = null
    /** Whether the mob is a sleeping Fels (it cannot be hurt until a player body wakes it). */
    fun isAsleep(id: UUID): Boolean = false
    /** Removes a mob without a kill (no event except none; no key). */
    fun remove(run: Run, id: UUID) {}

    /** Removes every mob (end of run, savepoint restore). */
    fun clearAll(run: Run) {}
}
