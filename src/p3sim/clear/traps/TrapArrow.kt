package com.engineerclient.p3sim.clear.traps

import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.ProjectileDeflection
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

/**
 * An arrow from a trap dispenser: vanilla flight (as the sim's other arrows), it hits only a human player (1.8's 0.3 margin) and the blocks, and is
 * gone at the first hit or after [maxTicks]. A player it hits is reported to [onHitPlayer]; the module takes the 15 percent and prints the line.
 * Never saved, never picked up.
 */
class TrapArrow(
    level: Level, x: Double, y: Double, z: Double,
    private val maxTicks: Int,
    private val isHuman: (Entity) -> Boolean,
    private val onHitPlayer: (ServerPlayer) -> Unit,
) : Arrow(level, x, y, z, ItemStack(Items.ARROW), null) {
    private var removeAt = -1

    init { pickup = AbstractArrow.Pickup.DISALLOWED }

    override fun shouldBeSaved() = false

    override fun tick() {
        if (removeAt >= 0 && tickCount >= removeAt) { discard(); return }
        if (tickCount > maxTicks) { discard(); return }
        super.tick()
    }

    override fun canHitEntity(entity: Entity): Boolean = removeAt < 0 && entity is ServerPlayer && entity.isAlive && isHuman(entity)

    override fun findHitEntities(from: Vec3, to: Vec3): Collection<EntityHitResult> =
        ProjectileUtil.getManyEntityHitResult(level(), this, from, to, boundingBox.expandTowards(deltaMovement).inflate(1.0), { canHitEntity(it) }, 0.3f, ClipContext.Block.COLLIDER, false, true)

    override fun hitTargetOrDeflectSelf(hitResult: HitResult): ProjectileDeflection {
        if (removeAt >= 0) return ProjectileDeflection.NONE   // already spent
        if (hitResult.type == HitResult.Type.ENTITY) {
            val p = (hitResult as EntityHitResult).entity as? ServerPlayer ?: return ProjectileDeflection.NONE
            // The hit sound is the arrow's, pitch on Hypixel's 1/63 steps; the arrow lingers a tick, then goes.
            val pitch = Mth.floor(1.2f / (Random.nextFloat() * 0.2f + 0.9f) * 63f) / 63f
            level().playSound(null, x, y, z, SoundEvents.ARROW_HIT, SoundSource.NEUTRAL, 1f, pitch)
            onHitPlayer(p)
            setNoGravity(true)
            deltaMovement = Vec3.ZERO
            removeAt = tickCount + 1
            return ProjectileDeflection.NONE
        }
        val d = super.hitTargetOrDeflectSelf(hitResult)   // a block: vanilla's hit sound
        if (hitResult.type == HitResult.Type.BLOCK) discard()
        return d
    }
}
