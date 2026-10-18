package com.engineerclient.mixin.odin;

import com.engineerclient.clearbreakdown.ClearBreakdown;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link ClearBreakdown} carries every player's secrets for the run, so while it is on, Odin's
 * Secrets Counter skips its end-of-run lookups and lines (its fetchAndDisplay). Odin's setting is
 * left alone: turning Clear Breakdown off brings them back.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.SecretsCounter", remap = false)
public class SecretsCounterMixin {
    @Inject(method = "fetchAndDisplay()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$clearBreakdown(CallbackInfo ci) {
        if (ClearBreakdown.INSTANCE.getEnabled()) ci.cancel();
    }
}
