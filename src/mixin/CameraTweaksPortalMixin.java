package com.engineerclient.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class CameraTweaksPortalMixin {

    @Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void ec$hidePortalOverlay(GuiGraphicsExtractor graphics, float alpha, CallbackInfo ci) {
        if (com.engineerclient.render.Visibility.hidesPortalOverlay()) ci.cancel();
    }
}
