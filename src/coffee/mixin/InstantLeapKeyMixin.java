package com.coffeeclient.mixin;

import com.coffeeclient.leap.InstantLeap;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leap Extras' Instant Open: a right-click with a leap opens the leap menu as it's pressed ({@link InstantLeap}). */
@Mixin(KeyMapping.class)
public class InstantLeapKeyMixin {
    @Inject(method = "click", at = @At("HEAD"), cancellable = true, require = 0)
    private static void cc$instantLeap(InputConstants.Key key, CallbackInfo ci) {
        try {
            if (InstantLeap.onPress(key)) ci.cancel();
        } catch (Throwable ignored) {
            // The click goes on as vanilla's.
        }
    }
}
