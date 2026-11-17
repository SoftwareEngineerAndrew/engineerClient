package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookShownMixin {
    // A book left open would still shift the screen and take clicks.
    @ModifyReturnValue(method = "isVisible", at = @At("RETURN"))
    private boolean ec$shown(boolean shown) {
        return shown && !Visibility.recipeBookHidden();
    }
}
