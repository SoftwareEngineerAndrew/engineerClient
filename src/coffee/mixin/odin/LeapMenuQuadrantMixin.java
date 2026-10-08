package com.coffeeclient.mixin.odin;

import com.coffeeclient.leap.LeapExtras;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Map Leap's click: Odin's leap menu turns every click (and, in "On Key Release" mode, every
 * release) into a corner of the screen in this one function, after its own checks - Click Delay
 * included. In map mode the head under the cursor is picked instead; see {@link LeapExtras#mapLeapClick}.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.LeapMenu", remap = false)
public class LeapMenuQuadrantMixin {

    @Inject(method = "_init_$triggerMouseQuadrant(Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cc$mapLeap(AbstractContainerScreen<?> screen, int x, int y, CallbackInfo ci) {
        if (LeapExtras.mapLeapClick(screen, x, y)) ci.cancel();
    }
}
