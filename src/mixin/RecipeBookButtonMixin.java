package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractRecipeBookScreen.class)
public abstract class RecipeBookButtonMixin {
    @WrapWithCondition(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/AbstractRecipeBookScreen;initButton()V"))
    private boolean ec$addButton(AbstractRecipeBookScreen<?> screen) {
        return !Visibility.recipeBookHidden();
    }
}
