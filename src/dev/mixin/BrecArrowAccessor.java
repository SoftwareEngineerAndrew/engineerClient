package com.devgineerclient.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Better Recorder: whether an arrow is stuck in the ground as the client has it, and for how many ticks. */
@Mixin(AbstractArrow.class)
public interface BrecArrowAccessor {
    @Invoker("isInGround")
    boolean brec$inGround();

    @Accessor("inGroundTime")
    int brec$inGroundTime();
}
