package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeBookComponent.class)
public abstract class HideRecipeBookComponentMixin {
    // A book left open shifts the screen and still takes clicks, so it reads as closed too.
    @Inject(method = "isVisible", at = @At("HEAD"), cancellable = true)
    private void ec$hideRecipeBook(CallbackInfoReturnable<Boolean> cir) {
        if (RandomStuff.INSTANCE.hidesRecipeBook()) cir.setReturnValue(false);
    }
}
