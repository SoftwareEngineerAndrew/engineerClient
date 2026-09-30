package com.engineerclient.mixin;

import com.engineerclient.leap.LeapExtras;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link LeapExtras}' Crouch Click Termsim: crouch + left click with Infinileap opens Odin's numbers
 * terminal simulator (the click is cancelled, so nothing is sent), and when that simulator is
 * finished, the termsim menu Odin opens next is swapped for no screen.
 */
@Mixin(Minecraft.class)
public class LeapTermsimMixin {

    @Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
    private void ec$leapTermsim(CallbackInfoReturnable<Boolean> cir) {
        if (LeapExtras.onAttack()) cir.setReturnValue(false);
    }

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen ec$leapTermsimDone(Screen screen) {
        return LeapExtras.replaceScreen(screen);
    }
}
