package com.engineerclient.mixin.odin;

import com.engineerclient.misc.OdinHighlightLook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Odin's Highlight drawing its starred-mob boxes (the render handler, Highlight$2): skipped while
 * the Blade look is on ({@link OdinHighlightLook}), which draws its own. Odin's tick handler still
 * runs, so Hide non-starred names keeps working.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.Highlight$2", remap = false)
public class HighlightRenderMixin {

    @Inject(method = "invoke(Lcom/odtheking/odin/events/RenderEvent$Extract;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$bladeLook(CallbackInfo ci) {
        if (OdinHighlightLook.replacesOdin()) ci.cancel();
    }
}
