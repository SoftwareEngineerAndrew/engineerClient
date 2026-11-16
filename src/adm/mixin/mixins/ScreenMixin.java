package com.adm.mixin.mixins;

import com.adm.events.ScreenEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
    private void onRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (new ScreenEvent.Render((Screen) (Object) this, graphics, mouseX, mouseY).postAndCatch()) ci.cancel();
    }
}