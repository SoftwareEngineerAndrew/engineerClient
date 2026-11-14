package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractRecipeBookScreen.class)
public abstract class HideRecipeBookScreenMixin {
    @Inject(method = "initButton", at = @At("HEAD"), cancellable = true)
    private void ec$hideRecipeButton(CallbackInfo ci) {
        if (RandomStuff.INSTANCE.hidesRecipeBook()) ci.cancel();
    }
}
