package com.engineerclient.mixin;

import com.engineerclient.render.CameraTweaks;
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
        if (CameraTweaks.INSTANCE.hidePortalOverlay()) ci.cancel();
    }
}
