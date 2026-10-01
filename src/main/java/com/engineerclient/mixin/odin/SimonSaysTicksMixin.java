package com.engineerclient.mixin.odin;

import com.engineerclient.practice.OdinSimonSays;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Odin's Simon Says works its answer out in two listeners, block changes (SimonSays$3) and server
 * ticks (SimonSays$4). With engineerClient's solver on ({@link OdinSimonSays}) this one doesn't run;
 * the answer is written into Odin's fields instead, and the rest of Odin's module uses it as its own.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.boss.SimonSays$4", remap = false)
public class SimonSaysTicksMixin {
    @Inject(method = "invoke(Lcom/odtheking/odin/events/TickEvent$Server;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$solver(CallbackInfo ci) {
        if (OdinSimonSays.active()) ci.cancel();
    }
}
