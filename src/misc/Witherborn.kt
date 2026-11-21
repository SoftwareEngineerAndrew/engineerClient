package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.adm.events.LevelEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.events.core.onReceive
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.boss.wither.WitherBoss
import java.util.concurrent.ConcurrentHashMap

/**
 * Storm armor's Witherborn: a full set summons a small wither that flies at nearby enemies and
 * explodes on them - in F7's boss, on the boss. To the client it is a wither like Maxor, so
 * anything looking for "the wither" can take it for him (his death when it explodes, a hit,
 * the wrong boss to aim at).
 *
 * Told apart by where it first appears: on its owner. In recorded F7/M7 runs every Witherborn
 * wither first appeared within 4 blocks of a player, and no boss closer than ~5 - bosses appear at
 * their own spots, usually 15+ blocks off. A boss coming
 * back into view next to someone keeps its entity id, so only an id's first sighting decides.
 * A wither scaled down (a smaller size attribute) counts as one too.
 */
object Witherborn {
    /** A wither first seen this close to a player is a Witherborn wither. */
    private const val NEAR = 4.5

    private val minions: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val seen: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    fun register() {
        // Network thread: decided as the spawn arrives, before anything else hears of the wither.
        onReceive<ClientboundAddEntityPacket>(priority = Int.MAX_VALUE) {
            if (type == EntityTypes.WITHER) onSpawn(id, x, y, z)
        }
        on<LevelEvent.Load> { minions.clear(); seen.clear() }
        EventBus.subscribe(this)
    }

    /** A wither [id] appearing at ([x], [y], [z]): true when it is a Witherborn wither. */
    @JvmStatic
    fun onSpawn(id: Int, x: Double, y: Double, z: Double): Boolean {
        if (id in seen) return id in minions
        // The game thread changes the player list meanwhile: a copy caught mid-change can hold nulls or fail, and is tried again next time.
        val near = runCatching { EngineerClient.mc.level?.players()?.toList()?.any { it != null && it.distanceToSqr(x, y, z) <= NEAR * NEAR } }.getOrNull() ?: return false
        if (near) minions += id
        seen += id
        return near
    }

    /** [e] is a Witherborn wither, not a boss. */
    @JvmStatic
    fun isMinion(e: Entity): Boolean = e.id in minions || (e is WitherBoss && e.scale < 0.9f)

    /** [e] is a wither that is a boss (Maxor, Storm, Goldor, Necron), not a Witherborn one. */
    @JvmStatic
    fun isBoss(e: Entity?): Boolean = e is WitherBoss && !isMinion(e)

    /** A sound at ([x], [y], [z]) is a Witherborn wither's: one is within 2 blocks of it. */
    fun soundFromMinion(x: Double, y: Double, z: Double): Boolean {
        val level = EngineerClient.mc.level ?: return false
        return minions.any { id -> level.getEntity(id)?.let { it.distanceToSqr(x, y, z) <= 4.0 } == true }
    }
}
