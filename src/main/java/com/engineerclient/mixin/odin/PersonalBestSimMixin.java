package com.engineerclient.mixin.odin;

import com.engineerclient.p3sim.P3Sim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Odin is told it is in F7's boss in the P3 Sim world, so its splits and terminal times would save
 * the sim's times as real personal bests. They still show there; they are never stored.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.utils.PersonalBest", remap = false)
public class PersonalBestSimMixin {
    @Inject(method = "set(Ljava/lang/String;F)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$notInSim(CallbackInfo ci) {
        if (P3Sim.getInSim()) ci.cancel();
    }
}
