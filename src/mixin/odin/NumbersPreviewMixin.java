package com.engineerclient.mixin.odin;

import com.engineerclient.practice.TermInfo;
import com.odtheking.odin.utils.Color;
import com.odtheking.odin.utils.skyblock.dungeon.terminals.terminalhandler.NumbersHandler;
import kotlin.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Odin's Numbers solver colours the solution's first four slots (Numbers 1-4) and leaves the rest
 * transparent; {@link TermInfo}'s "Numbers 4th/5th Preview" colours the 4th (over Odin's) and 5th, keeping Odin's text.
 */
@Mixin(value = NumbersHandler.class, remap = false)
public class NumbersPreviewMixin {
    @Inject(method = "renderSlot", at = @At("RETURN"), cancellable = true, remap = false)
    private void ec$moreOrder(int slot, CallbackInfoReturnable<Pair<Color, String>> cir) {
        Color color = TermInfo.numbersColor(((NumbersHandler) (Object) this).getSolution().indexOf(slot));
        if (color != null) cir.setReturnValue(new Pair<>(color, cir.getReturnValue().getSecond()));
    }
}
