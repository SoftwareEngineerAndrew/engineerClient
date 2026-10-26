package com.coffeeclient.mixin.odin;

import com.coffeeclient.leap.InstantLeap;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leap Extras' Instant Open: a leap picked on the stand-in menu (every way Odin leaps ends here) is held and clicked in the
 * real one when it comes ({@link InstantLeap}).
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.LeapMenu", remap = false)
public class LeapMenuLeapToMixin {
    @Inject(method = "leapTo(Ljava/lang/String;Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void cc$held(String name, AbstractContainerScreen<?> screen, CallbackInfo ci) {
        try {
            if (InstantLeap.onLeap(name, screen)) ci.cancel();
        } catch (Throwable ignored) {
            // Odin's own leap, then.
        }
    }
}
