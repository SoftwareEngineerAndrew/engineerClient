package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Random Stuff's Item Swing Speed: the local player's swing animation lasts its usual length
 * divided by the speed. The length is in whole ticks, so fast speeds round (6 ticks at 1x, 3 at 2x).
 * Only the animation: attacks and clicks are untouched.
 */
@Mixin(LivingEntity.class)
public abstract class SwingSpeedMixin {
    @Inject(method = "getModifiedSwingDuration", at = @At("RETURN"), cancellable = true)
    private void ec$swingSpeed(SwingAnimation animation, CallbackInfoReturnable<Integer> cir) {
        if ((Object) this != Minecraft.getInstance().player) return;
        float speed = RandomStuff.INSTANCE.swingSpeed();
        if (speed != 1f) cir.setReturnValue(Math.max(1, Math.round(cir.getReturnValue() / speed)));
    }
}
