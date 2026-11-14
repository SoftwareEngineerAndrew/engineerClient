package com.engineerclient.mixin;

import com.engineerclient.dungeon.Dungeonbreaker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class DungeonbreakerMixin {
    @Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
    private void ec$torchGuardAttack(CallbackInfoReturnable<Boolean> cir) {
        if (Dungeonbreaker.blocksAttack()) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
    private void ec$torchGuardHold(boolean attacking, CallbackInfo ci) {
        if (attacking && Dungeonbreaker.blocksAttack()) ci.cancel();
    }
}
