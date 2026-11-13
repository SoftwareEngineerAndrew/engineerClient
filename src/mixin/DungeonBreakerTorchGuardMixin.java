package com.engineerclient.mixin;

import com.engineerclient.dungeon.DungeonBreakerTorchGuard;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class DungeonBreakerTorchGuardMixin {
    @Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
    private void ec$torchGuardAttack(CallbackInfoReturnable<Boolean> cir) {
        if (DungeonBreakerTorchGuard.blocksAttack()) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
    private void ec$torchGuardHold(boolean attacking, CallbackInfo ci) {
        if (attacking && DungeonBreakerTorchGuard.blocksAttack()) ci.cancel();
    }
}
