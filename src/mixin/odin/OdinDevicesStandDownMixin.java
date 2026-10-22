package com.engineerclient.mixin.odin;

import com.engineerclient.devices.DeviceSolvers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In ADS's worlds (the P3 Sim, Rustclear's) ADS's own device solvers draw ({@link DeviceSolvers}), so Odin's Simon
 * Says, Arrows Device and Arrow Align don't: their drawing (SimonSays$6, ArrowsDevice$2, ArrowAlign$3) and their
 * click handlers (Simon Says' Block Wrong Clicks and announcements, SimonSays$5; Arrow Align's, ArrowAlign$2) are
 * skipped there. Their working-out still runs, so Odin's state is as it would be when you leave. Elsewhere (Hypixel)
 * nothing changes.
 */
@Pseudo
@Mixin(targets = {
    "com.odtheking.odin.features.impl.boss.SimonSays$5",
    "com.odtheking.odin.features.impl.boss.SimonSays$6",
    "com.odtheking.odin.features.impl.boss.ArrowsDevice$2",
    "com.odtheking.odin.features.impl.boss.ArrowAlign$2",
    "com.odtheking.odin.features.impl.boss.ArrowAlign$3",
}, remap = false)
public class OdinDevicesStandDownMixin {
    @Inject(method = "invoke(Lcom/odtheking/odin/events/RenderExtractEvent;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$noDraw(CallbackInfo ci) {
        if (DeviceSolvers.inAdsWorld()) ci.cancel();
    }

    @Inject(method = "invoke(Lcom/odtheking/odin/events/BlockInteractEvent;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$noBlockClick(CallbackInfo ci) {
        if (DeviceSolvers.inAdsWorld()) ci.cancel();
    }

    @Inject(method = "invoke(Lcom/odtheking/odin/events/EntityInteractEvent;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$noEntityClick(CallbackInfo ci) {
        if (DeviceSolvers.inAdsWorld()) ci.cancel();
    }
}
