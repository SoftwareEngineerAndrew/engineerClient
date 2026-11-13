package com.engineerclient.mixin;

import com.engineerclient.render.DarkMode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class DarkModeMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void ec$tintBeforeHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        DarkMode.INSTANCE.draw(graphics, false);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void ec$tintAfterHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        DarkMode.INSTANCE.draw(graphics, true);
    }
}
