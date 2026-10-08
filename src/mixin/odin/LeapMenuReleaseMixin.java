package com.engineerclient.mixin.odin;

import com.engineerclient.leap.LeapExtras;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Click Delay for Odin's "On Key Release" mode: the release handler (LeapMenu$4), held off the same way. */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.LeapMenu$4", remap = false)
public class LeapMenuReleaseMixin {

    @Inject(method = "invoke(Lcom/odtheking/odin/events/ScreenEvent$MouseRelease;)Ljava/lang/Object;", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$clickDelay(CallbackInfoReturnable<Object> cir) {
        if (LeapExtras.inClickDelay()) cir.setReturnValue(true);
    }
}
