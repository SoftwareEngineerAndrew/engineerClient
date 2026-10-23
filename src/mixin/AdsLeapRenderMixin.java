package com.engineerclient.mixin;

import com.engineerclient.ads.leap.AdsLeapMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ADS's leap menu: drawn instead of the whole Spirit Leap chest, as the terminal solver's custom GUI is. */
@Mixin(Screen.class)
public abstract class AdsLeapRenderMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
    private void ads$leapMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (!((Object) this instanceof AbstractContainerScreen<?> screen) || !AdsLeapMenu.active(screen)) return;
        try {
            AdsLeapMenu.render(screen, graphics, mouseX, mouseY);
            ci.cancel();
        } catch (Throwable t) {
            // the chest, as the game draws it, beats a blank screen
        }
    }
}
