package com.engineerclient.mixin;

import com.engineerclient.ads.leap.AdsLeapMenu;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ADS's leap menu on the Spirit Leap chest (AdsLeapMenu, only while it takes that chest over): its clicks, releases
 * and keys. The drawing is AdsLeapRenderMixin's.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AdsLeapScreenMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void ads$leapClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (AdsLeapMenu.active(screen) && AdsLeapMenu.click(screen, event.x(), event.y(), false)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void ads$leapRelease(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (AdsLeapMenu.active(screen) && AdsLeapMenu.click(screen, event.x(), event.y(), true)) cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void ads$leapKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (AdsLeapMenu.active(screen) && AdsLeapMenu.key(screen, event)) cir.setReturnValue(true);
    }
}
