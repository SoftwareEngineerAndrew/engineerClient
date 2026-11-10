package com.engineerclient.mixin;

import com.engineerclient.misc.DropAnimation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Attacking ends the held-item pin of {@link DropAnimation}, as the swing is its own animation. */
@Mixin(Minecraft.class)
public class DropAnimationAttackMixin {
    @Inject(method = "startAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/HitResult;getType()Lnet/minecraft/world/phys/HitResult$Type;"))
    private void ec$dropAttack(CallbackInfoReturnable<Boolean> cir) {
        DropAnimation.clearData();
    }
}
