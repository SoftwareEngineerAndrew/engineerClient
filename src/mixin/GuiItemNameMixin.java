package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The name that pops up over the hotbar when you switch items: hidden, or drawn by Visibility's Item Name HUD. */
@Mixin(Hud.class)
public class GuiItemNameMixin {

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void ec$hideSelectedItemName(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (Visibility.INSTANCE.hidesItemNames()) ci.cancel();
    }
}
