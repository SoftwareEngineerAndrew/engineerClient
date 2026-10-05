package com.engineerclient.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code AbstractArrow.isInGround()} is protected; Hide Own Arrows Nearby needs it. */
@Mixin(AbstractArrow.class)
public interface ArrowInGroundInvoker {
    @Invoker("isInGround")
    boolean ec$isInGround();
}
