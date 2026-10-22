package com.engineerclient.ads

import com.engineerclient.p3sim.SimServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.rustclear.mod.engine.EngineServer

/**
 * In ADS's own worlds (the P3 Sim and the dungeon engine's) players take no knockback at all: not from hits, arrows
 * or mobs, explosions, or bodies bumping into them (NoPlayerKnockbackMixin, NoLivingKnockbackMixin). What a player's
 * own abilities do (Bonzo's Staff, leaps, the Superboom) and lava's bounce set the velocity directly, so they still
 * work. Server side, on those worlds only: anywhere else, Hypixel included, nothing changes.
 */
object NoKnockback {
    @JvmStatic
    fun applies(entity: Entity): Boolean {
        if (entity !is ServerPlayer) return false
        val level = entity.level()
        return EngineServer.isEngineLevel(level) || SimServer.isSimLevel(level)
    }
}
