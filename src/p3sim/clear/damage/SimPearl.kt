package com.engineerclient.p3sim.clear.damage

import com.engineerclient.p3sim.Sim
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Relative
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * The ender pearl a player throws in a full run's clear. It flies as vanilla's does (out of the hand at [ClearItems.PEARL_SPEED], the
 * same spread) and lands the way the recordings show Hypixel's do: 32 portal puffs where it hit, the thrower put at the pearl's position
 * of the tick before (their look kept, their fall reset), one enderman teleport sound, and then it is gone. Unlike vanilla's it costs the
 * thrower no health and spawns no endermite, and a mob or a mate it hits is only a place to land: nothing takes damage.
 */
class SimPearl(level: Level, owner: LivingEntity, stack: ItemStack) : ThrownEnderpearl(level, owner, stack) {

    override fun onHitEntity(hit: EntityHitResult) {
        // Vanilla would hurt the target for 0; here the pearl just lands on it.
    }

    override fun onHit(hit: HitResult) {
        val level = level() as? ServerLevel ?: return
        if (isRemoved) return
        repeat(ClearItems.PEARL_PUFFS) {
            level.sendParticles(ParticleTypes.PORTAL, false, true, x, y + random.nextDouble() * 2.0, z, 1, random.nextGaussian(), 0.0, random.nextGaussian(), 0.0)
        }
        val thrower = owner as? ServerPlayer
        if (thrower != null && thrower.isAlive && !thrower.isSleeping && thrower.level() === level) {
            val to = oldPosition()
            thrower.teleportTo(level, to.x, to.y, to.z, Relative.ROTATION, 0f, 0f, false)
            thrower.deltaMovement = Vec3.ZERO
            thrower.fallDistance = 0.0
        }
        Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1f, position(), SoundSource.HOSTILE)
        discard()
    }
}
