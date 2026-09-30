package com.engineerclient.mixin;

import com.engineerclient.leap.LeapExtras;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The numbers simulator {@link LeapExtras#openNumbersSim} opens: when one is finished, the termsim
 * menu Odin opens next is kept off so the next one can start in its place.
 */
@Mixin(Minecraft.class)
public class LeapTermsimMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void ec$leapTermsimNext(Screen screen, CallbackInfo ci) {
        if (LeapExtras.cancelScreen(screen)) ci.cancel();
    }
}
