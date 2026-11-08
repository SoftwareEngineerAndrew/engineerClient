package com.engineerclient.mixin.odin;

import com.engineerclient.waterboard.WaterBoardSolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Odin's Water Board solver drawing its fixed timings: the dynamic solver ({@link WaterBoardSolver}) draws its own plan instead
 * while it has one, so the two don't show different times on the same levers.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.puzzlesolvers.WaterSolver", remap = false)
public class WaterSolverRenderMixin {

    @Inject(method = "onRenderWorld", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ec$dynamicWater(CallbackInfo ci) {
        if (WaterBoardSolver.drawsInstead()) ci.cancel();
    }
}
