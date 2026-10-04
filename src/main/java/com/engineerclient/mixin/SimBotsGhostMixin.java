package com.engineerclient.mixin;

import com.engineerclient.p3sim.P3Sim;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The P3 Sim's party (mannequins) touch nothing: they don't push you or get pushed, and your
 * crosshair, clicks and arrows go through them. Client and integrated server alike; only in the sim.
 */
@Mixin(LivingEntity.class)
public class SimBotsGhostMixin {
    private boolean ec$simBot() {
        return (Object) this instanceof Mannequin && P3Sim.getInSim();
    }

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void ec$notPushable(CallbackInfoReturnable<Boolean> cir) {
        if (ec$simBot()) cir.setReturnValue(false);
    }

    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
    private void ec$notPickable(CallbackInfoReturnable<Boolean> cir) {
        if (ec$simBot()) cir.setReturnValue(false);
    }

    @Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
    private void ec$noPushing(CallbackInfo ci) {
        if (ec$simBot()) ci.cancel();
    }
}
