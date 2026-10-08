package com.coffeeclient.mixin.odin;

import com.coffeeclient.leap.LeapExtras;
import com.odtheking.odin.events.ScreenEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leap Extras' Map Leap: in the clear, the map is drawn instead of the boxes of Odin's leap menu
 * (the render handler, compiled into LeapMenu$2), and true is Odin's own "drawn" answer. Odin only
 * calls this handler while its leap menu is the screen (its HandlerSet's active check), so nothing
 * else ever gets the map.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.LeapMenu$2", remap = false)
public class LeapMenuMapMixin {

    @Inject(method = "invoke(Lcom/odtheking/odin/events/ScreenEvent$Render;)Ljava/lang/Object;", at = @At("HEAD"), cancellable = true, remap = false)
    private void cc$mapLeap(ScreenEvent.Render event, CallbackInfoReturnable<Object> cir) {
        if (LeapExtras.renderMapLeap(event)) cir.setReturnValue(true);
    }
}
