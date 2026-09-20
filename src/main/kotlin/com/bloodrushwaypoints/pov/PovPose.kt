package com.bloodrushwaypoints.pov

import com.bloodrushwaypoints.mixin.LivingEntityAccessor
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.LivingEntity

/**
 * Decides where a teammate's eyes are for a POV preview pass — the "interpolate head
 * movements" option. See docs/pov-preview-plan.md, section PovPose.
 *
 * Contract (PovCapture relies on exactly this):
 *  - [begin] may rewrite the entity's position/rotation lerp fields so that the following
 *    `Camera.update(delta)` sees the wanted pose; it returns a [Restore] that puts every
 *    field back. Call [Restore.restore] in a `finally`.
 *  - [onClientTick] is called once per client tick (render thread) so CUSTOM can sample.
 *
 * What the camera actually reads (26.1.2 `Camera.alignWithEntity`, first person):
 *  `Mth.lerp(partialTick, entity.xo/yo/zo, entity.getX/Y/Z())` for the eye position,
 *  `entity.getViewYRot(partialTick)` = `Mth.rotLerp(p, yHeadRotO, yHeadRot)` on LivingEntity,
 *  and `entity.getViewXRot(partialTick)` = `Mth.lerp(p, xRotO, getXRot())`.
 * So writing the wanted pose into BOTH halves of each pair makes the partial-tick lerp a no-op
 * and the camera lands exactly on the pose, whatever `partialTick` happens to be.
 */
object PovPose {

    enum class Mode { VANILLA, RAW, CUSTOM }

    fun interface Restore {
        fun restore()
    }

    private val NONE = Restore {}

    /** One ring per remote player, fed by [onClientTick], read by CUSTOM. */
    private val interpolator = PovInterpolator()

    private const val TICK_NANOS = 50_000_000L

    fun begin(entity: LivingEntity, mode: Mode, ticks: Int, partialTick: Float): Restore =
        when (mode) {
            // Camera.update(delta) already lerps yHeadRotO -> yHeadRot: smooth, ~3 ticks behind.
            Mode.VANILLA -> NONE
            Mode.RAW -> apply(entity, packetTarget(entity))
            Mode.CUSTOM -> {
                if (ticks <= 0) {
                    apply(entity, packetTarget(entity)) // ticks = 0 is RAW by definition
                } else {
                    val at = System.nanoTime() - ticks * TICK_NANOS
                    val pose = interpolator.poseAt(entity.uuid, at) ?: packetTarget(entity)
                    apply(entity, pose)
                }
            }
        }

    /** Samples every remote player's current packet target. Cheap: a handful of players. */
    fun onClientTick() {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null) {
            interpolator.clear()
            return
        }
        val self = mc.player
        val now = System.nanoTime()
        for (player in level.players()) {
            if (player === self) continue
            interpolator.sample(player.uuid, now, packetTarget(player))
        }
        interpolator.evict(now)
    }

    /** Forgets all history — world change, or the feature being switched off. */
    fun reset() = interpolator.clear()

    /**
     * The latest thing the server said about this player: the interpolation target while one is
     * running (that is the packet's position/pitch), the live value otherwise; head yaw is
     * `lerpYHeadRot` while `lerpHeadSteps > 0` (set by `lerpHeadTo(yHeadRot, 3)` on every
     * rotation packet), else the settled `yHeadRot`.
     */
    private fun packetTarget(entity: LivingEntity): PovInterpolator.Pose {
        val interp = entity.interpolation
        val active = interp != null && interp.hasActiveInterpolation()
        val pos = if (active) interp!!.position() else entity.position()
        val pitch = if (active) interp!!.xRot() else entity.xRot
        val accessor = entity as LivingEntityAccessor
        val headYaw =
            if (accessor.brw_getLerpHeadSteps() > 0) accessor.brw_getLerpYHeadRot().toFloat()
            else entity.yHeadRot
        return PovInterpolator.Pose(pos.x, pos.y, pos.z, headYaw, pitch)
    }

    /**
     * Writes [pose] into both the "old" and the current half of every field the camera lerps,
     * and hands back the undo. Position goes through `setPos` so the bounding box (which
     * `Camera.getMaxZoom` and the entity renderer read) stays consistent with it.
     */
    private fun apply(entity: LivingEntity, pose: PovInterpolator.Pose): Restore {
        val oldPos = entity.position()
        val oldXo = entity.xo
        val oldYo = entity.yo
        val oldZo = entity.zo
        val oldXRot = entity.xRot
        val oldXRotO = entity.xRotO
        val oldHeadYaw = entity.yHeadRot
        val oldHeadYawO = entity.yHeadRotO

        entity.setPos(pose.x, pose.y, pose.z)
        entity.xo = pose.x
        entity.yo = pose.y
        entity.zo = pose.z
        entity.xRot = pose.pitch
        entity.xRotO = pose.pitch
        entity.yHeadRot = pose.headYaw
        entity.yHeadRotO = pose.headYaw

        return Restore {
            entity.setPos(oldPos.x, oldPos.y, oldPos.z)
            entity.xo = oldXo
            entity.yo = oldYo
            entity.zo = oldZo
            entity.xRot = oldXRot
            entity.xRotO = oldXRotO
            entity.yHeadRot = oldHeadYaw
            entity.yHeadRotO = oldHeadYawO
        }
    }
}
