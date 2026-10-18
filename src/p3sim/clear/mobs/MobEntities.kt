package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.p3sim.SimStand
import com.engineerclient.p3sim.Station
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Avatar
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.Mannequin
import net.minecraft.world.entity.monster.Enderman
import net.minecraft.world.entity.monster.Silverfish
import net.minecraft.world.entity.monster.skeleton.Skeleton
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.Level

/*
 * The bodies of the clear's mobs. Each is the vanilla type it looks like, with the vanilla brain taken out (no goals: nothing
 * targets a player or burns in daylight; the module drives movement itself), immune to vanilla damage (the mob's HP is the
 * SkyBlock number the module keeps and the damage module changes), and NOT saved with its chunk: a body whose chunk unloads simply
 * vanishes and the module builds it again from its record when the chunk is back. That keeps a stale vanilla copy from ever
 * appearing in the world.
 */

/** Marker for the bodies this module makes. */
interface AdsMobBody

class AdsZombie(level: Level) : Zombie(EntityTypes.ZOMBIE, level), AdsMobBody {
    override fun registerGoals() {}
    override fun isSunSensitive(): Boolean = false
    override fun convertsInWater(): Boolean = false
    override fun shouldBeSaved(): Boolean = false
    override fun fireImmune(): Boolean = true
}

class AdsSkeleton(level: Level) : Skeleton(EntityTypes.SKELETON, level), AdsMobBody {
    override fun registerGoals() {}
    override fun shouldBeSaved(): Boolean = false
    override fun fireImmune(): Boolean = true
}

class AdsWitherSkeleton(level: Level) : WitherSkeleton(EntityTypes.WITHER_SKELETON, level), AdsMobBody {
    override fun registerGoals() {}
    override fun shouldBeSaved(): Boolean = false
    override fun fireImmune(): Boolean = true
}

class AdsEnderman(level: Level) : Enderman(EntityTypes.ENDERMAN, level), AdsMobBody {
    override fun registerGoals() {}
    /** An enderman teleports when it is hurt or rained on; this one stays where the module puts it. */
    override fun teleport(): Boolean = false
    override fun shouldBeSaved(): Boolean = false
}

class AdsSilverfish(level: Level) : Silverfish(EntityTypes.SILVERFISH, level), AdsMobBody {
    override fun registerGoals() {}
    override fun shouldBeSaved(): Boolean = false
}

/**
 * A fake player's server body. It is an [Avatar] (a mannequin-like living entity with a player's skin parts and hand, and
 * nothing else), so the server keeps a light mob while the client is told it is a real `Player`: [getAddEntityPacket] sends a
 * `PLAYER` add with this entity's version-2 UUID, the player-info entry having been sent before (see [Disguise]). An Avatar's
 * synched data stops at id 16, exactly the ids a Hypixel fake player sends, so the client's `RemotePlayer` never meets a
 * Mannequin-only data id (profile, immovable, description: ids 17 to 19 would fail its serializer check and crash it).
 */
class AdsDisguise(level: Level) : Avatar(EntityTypes.MANNEQUIN, level), AdsMobBody {
    override fun getProfile(): ResolvableProfile = Mannequin.DEFAULT_PROFILE
    override fun getAddEntityPacket(entity: ServerEntity): Packet<ClientGamePacketListener> =
        ClientboundAddEntityPacket(id, uuid, x, y, z, xRot, yRot, EntityTypes.PLAYER, 0, deltaMovement, yHeadRot.toDouble())
    override fun shouldBeSaved(): Boolean = false
    override fun fireImmune(): Boolean = true
    override fun isPushable(): Boolean = false

    /** Without this a living entity that is not a Mob never runs its own movement on the server: no gravity, no walking. */
    override fun isEffectiveAi(): Boolean = true
}

/** What [MobBodies.build] returns: a body, the entities allocated between it and its name tag, and the tag. */
class BuiltMob(val body: Entity, val fillers: List<Entity>, val stand: SimStand)

/** Making bodies and their tags, and the bits of entity data the module writes. */
object MobBodies {
    private val log = org.slf4j.LoggerFactory.getLogger("engineerclient")

    /**
     * Builds a body, `idOffset - 1` invisible nameless stands and the tag stand so that the tag's entity id is exactly the body's
     * plus [idOffset] (Odin's and EC's id arithmetic: 1 for everything, 3 for the Withermancer). Ids come from a counter the client
     * thread also draws from, so a draw by another thread in the middle makes a gap: then the lot is thrown away unused and tried
     * again; as the last resort the ids are forced when the wanted id is free in the level.
     */
    fun build(level: ServerLevel, idOffset: Int, makeBody: () -> Entity): BuiltMob {
        repeat(12) {
            val body = makeBody()
            val fillers = (1 until idOffset).map { filler(level) }
            val stand = SimStand(level)
            if (stand.id == body.id + idOffset && fillers.withIndex().all { (i, f) -> f.id == body.id + 1 + i }) return BuiltMob(body, fillers, stand)
        }
        val body = makeBody()
        val fillers = (1 until idOffset).map { filler(level) }
        val stand = SimStand(level)
        fillers.forEachIndexed { i, f -> if (level.getEntity(body.id + 1 + i) == null) f.setId(body.id + 1 + i) }
        if (level.getEntity(body.id + idOffset) == null) stand.setId(body.id + idOffset)
        log.warn("[ads] mob ids were not consecutive after 12 tries; forced body ${body.id} tag ${stand.id}")
        return BuiltMob(body, fillers, stand)
    }

    private fun filler(level: ServerLevel): SimStand = SimStand(level).also { it.isInvisible = true; it.isSilent = true; it.isPermanentlyInvulnerable = true; Station.setMarker(it) }

    /** Makes the name tag stand invisible, marker-sized, with a visible custom name. */
    fun dressTag(stand: SimStand, name: net.minecraft.network.chat.Component) {
        stand.isInvisible = true
        stand.isSilent = true
        stand.isPermanentlyInvulnerable = true
        stand.setNoGravity(true)
        Station.setMarker(stand)
        stand.customName = name
        stand.isCustomNameVisible = true
    }

    private val healthAccessor: EntityDataAccessor<Float>? by lazy {
        try {
            val f = LivingEntity::class.java.getDeclaredField("DATA_HEALTH_ID")
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST") (f.get(null) as EntityDataAccessor<Float>)
        } catch (t: Throwable) { log.warn("[ads] cannot reach the health data id: ${t.message}"); null }
    }

    /**
     * Writes the SkyBlock HP into the body's health data (index 9), where Hypixel keeps it too, so recorders that read the entity
     * data agree with the tag. Best effort: false when the id cannot be reached. The vanilla max-health attribute is left alone
     * (the client shows 20 or 40 there on Hypixel as well).
     */
    fun setHealthData(body: LivingEntity, hp: Double): Boolean {
        val acc = healthAccessor ?: return false
        return try { body.entityData.set(acc, hp.toFloat()); true } catch (t: Throwable) { false }
    }

    /** A packet that sets the body's health to [hp] for everyone tracking it, sent just before the body is removed (a death is HP 0, then gone on the same tick). */
    fun healthPacket(body: Entity, hp: Float): ClientboundSetEntityDataPacket? {
        val acc = healthAccessor ?: return null
        return ClientboundSetEntityDataPacket(body.id, listOf(SynchedEntityData.DataValue.create(acc, hp)))
    }
}
