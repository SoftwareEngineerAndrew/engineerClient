package com.engineerclient.mixin;

import com.engineerclient.p3sim.clear.replay.ReplayClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While an ADS replay drives your player, your clicks do nothing (the recording's are played instead). */
@Mixin(Minecraft.class)
public abstract class AdsReplayClientMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void ec$replayAttack(CallbackInfoReturnable<Boolean> cir) { if (ReplayClient.active) cir.setReturnValue(false); }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void ec$replayUse(CallbackInfo ci) { if (ReplayClient.active) ci.cancel(); }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void ec$replayContinue(boolean down, CallbackInfo ci) { if (ReplayClient.active) ci.cancel(); }

    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void ec$replayPick(CallbackInfo ci) { if (ReplayClient.active) ci.cancel(); }
}
