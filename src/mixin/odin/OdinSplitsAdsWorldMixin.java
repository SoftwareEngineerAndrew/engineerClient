package com.engineerclient.mixin.odin;

import com.engineerclient.ads.AdsWorlds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;

/**
 * In ADS's worlds (the P3 Sim, Rustclear's) ADS times the run with its own Splits, so Odin's SplitsManager sits
 * out: no run starts, no line splits it (so no "took" lines or PBs of Odin's), and it has no rows for its HUDs or
 * anything else that reads them. Everywhere else, Hypixel included, Odin's Splits run as they always do.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.utils.skyblock.SplitsManager", remap = false)
public class OdinSplitsAdsWorldMixin {
    @Inject(method = "startRun", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$noRunInAdsWorlds(CallbackInfo ci) {
        if (AdsWorlds.getActive()) ci.cancel();
    }

    @Inject(method = "onSplitMessage", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$noSplitInAdsWorlds(String message, CallbackInfo ci) {
        if (AdsWorlds.getActive()) ci.cancel();
    }

    @Inject(method = "currentRows", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$noRowsInAdsWorlds(CallbackInfoReturnable<List<?>> cir) {
        if (AdsWorlds.getActive()) cir.setReturnValue(Collections.emptyList());
    }
}
