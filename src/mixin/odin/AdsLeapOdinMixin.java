package com.engineerclient.mixin.odin;

import com.engineerclient.ads.leap.AdsLeapMenu;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Odin's Leap Menu steps aside while ADS's is on: its "which leap chest is open" check finds none, so it neither
 * draws nor takes clicks or keys (and Leap Extras, which adds to it, stays out too).
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.LeapMenu", remap = false)
public class AdsLeapOdinMixin {

    @Inject(method = "currentLeapScreen()Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ads$standAside(CallbackInfoReturnable<AbstractContainerScreen<?>> cir) {
        if (AdsLeapMenu.replacesOdin()) cir.setReturnValue(null);
    }
}
