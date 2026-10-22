package com.engineerclient.mixin;

import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No "New Recipe(s) Unlocked!" toasts: ADS's worlds have no crafting (the tutorial's toasts go in CleanHud). */
@Mixin(RecipeToast.class)
public class HideRecipeToastMixin {
    @Inject(method = "addOrUpdate", at = @At("HEAD"), cancellable = true)
    private static void ads$hideRecipeToast(ToastManager manager, RecipeDisplay recipe, CallbackInfo ci) {
        ci.cancel();
    }
}
