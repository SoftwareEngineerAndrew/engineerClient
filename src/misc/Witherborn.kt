package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
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
 * Told apart by where it first appears: on its owner. In 219 recorded F7/M7 runs since the boss
 * update, 216 of 222 Witherborn withers first showed within 3 blocks of a player (all within 4),
 * and no boss within 4.8 - they appear at their own spots, 15+ blocks off as a rule. A boss coming
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
        if (!seen.add(id)) return id in minions
        val players = runCatching { EngineerClient.mc.level?.players()?.toList() }.getOrNull() ?: return false
        val near = players.any { it.distanceToSqr(x, y, z) <= NEAR * NEAR }
        if (near) minions += id
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
