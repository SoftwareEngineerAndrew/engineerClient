package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
public class CraftingTextMixin {
    @Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true)
    private void ec$noCraftingText(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (Visibility.hidesCraftingText()) ci.cancel();
    }
}
