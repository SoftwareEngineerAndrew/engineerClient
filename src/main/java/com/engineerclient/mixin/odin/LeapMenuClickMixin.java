package com.engineerclient.mixin.odin;

import com.engineerclient.leap.LeapExtras;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leap Extras' Click Delay: for the first few ticks the leap menu is open, Odin's click handler
 * (LeapMenu$3) takes the click and does nothing with it, so letting go of the right-click that
 * opened the menu can't leap you by accident. Returning true is Odin's own "handled" answer.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.LeapMenu$3", remap = false)
public class LeapMenuClickMixin {

    @Inject(method = "invoke(Lcom/odtheking/odin/events/ScreenEvent$MouseClick;)Ljava/lang/Object;", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$clickDelay(CallbackInfoReturnable<Object> cir) {
        if (LeapExtras.inClickDelay()) cir.setReturnValue(true);
    }
}
