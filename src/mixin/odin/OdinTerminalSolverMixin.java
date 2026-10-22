package com.engineerclient.mixin.odin;

import com.engineerclient.terminals.OdinTerminalGuard;
import com.odtheking.odin.features.impl.boss.TerminalSolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Odin's Terminal Solver standing aside while ADS's has the terminal open (OdinTerminalGuard): its GUI scale and its
 * custom GUI, the two parts of it read from outside its event listeners, say off.
 */
@Mixin(value = TerminalSolver.class, remap = false)
public class OdinTerminalSolverMixin {
    @Inject(method = "getOverridesGuiScale", at = @At("HEAD"), cancellable = true)
    private static void ec$noGuiScale(CallbackInfoReturnable<Boolean> cir) {
        if (OdinTerminalGuard.standsAside()) cir.setReturnValue(false);
    }

    @Inject(method = "getCustomGuiEnabled", at = @At("HEAD"), cancellable = true)
    private void ec$noCustomGui(CallbackInfoReturnable<Boolean> cir) {
        if (OdinTerminalGuard.standsAside()) cir.setReturnValue(false);
    }
}
