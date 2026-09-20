package com.bloodrushwaypoints.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The head-yaw packet target lives in two protected fields on {@link LivingEntity}:
 * {@code lerpYHeadRot} is what the server last sent and {@code lerpHeadSteps} counts the ticks
 * left of the 3-tick lerp towards it. POV previews read them to render a teammate's head where
 * the server says it is rather than where the smoothing has got to.
 *
 * Read-only; everything else the previews write ({@code yHeadRot}, {@code yHeadRotO},
 * {@code xo/yo/zo}, {@code xRotO}) is already public.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {

    @Accessor("lerpYHeadRot")
    double brw_getLerpYHeadRot();

    @Accessor("lerpHeadSteps")
    int brw_getLerpHeadSteps();
}
