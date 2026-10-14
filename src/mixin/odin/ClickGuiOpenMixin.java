package com.engineerclient.mixin.odin;

import com.engineerclient.splits.OdinSplitsLook;
import com.odtheking.odin.clickgui.ClickGUI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Odin's menu opening: the Pace target boxes' grayed stand-ins (the player's PBs) are brought up to date. */
@Mixin(value = ClickGUI.class, remap = false)
public class ClickGuiOpenMixin {
    @Inject(method = "init", at = @At("RETURN"), remap = false)
    private void ec$opened(CallbackInfo ci) {
        OdinSplitsLook.refreshPlaceholders();
    }
}
