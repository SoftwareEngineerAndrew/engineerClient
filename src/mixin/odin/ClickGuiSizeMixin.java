package com.engineerclient.mixin.odin;

import com.engineerclient.splits.OdinSplitsLook;
import com.odtheking.odin.clickgui.ClickGUI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Odin's menu opening: the Pace target boxes' grayed stand-ins (the player's PBs) are brought up to
 * date. (Random Stuff's Click GUI Size is not on the Minecraft 26.1.2 build: Odin 0.3.4's menu has
 * no scale field to set.)
 */
@Mixin(value = ClickGUI.class, remap = false)
public class ClickGuiSizeMixin {
    @Inject(method = "init", at = @At("RETURN"), remap = false)
    private void ec$size(CallbackInfo ci) {
        OdinSplitsLook.refreshPlaceholders();
    }
}
