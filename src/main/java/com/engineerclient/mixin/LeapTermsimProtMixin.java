package com.engineerclient.mixin;

import com.odtheking.odin.utils.skyblock.dungeon.terminals.terminalhandler.TerminalHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No first click protection on /termsim inf (InfNumbersSim), so the
 * next terminal can be clicked the moment it's up. Real terminals keep Odin's protection.
 */
@Mixin(value = TerminalHandler.class, remap = false)
public class LeapTermsimProtMixin {
    @Inject(method = "shouldProtect", at = @At("HEAD"), cancellable = true)
    private void ec$noProt(CallbackInfoReturnable<Boolean> cir) {
        if (com.engineerclient.practice.InfNumbersSim.active()) cir.setReturnValue(false);
    }
}
