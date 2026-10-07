package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps vanilla from marking a second blur once {@code GuiBlurMixin} has marked the first.
 *
 * <p>A frame may be told to blur exactly once — the game throws otherwise. On a menu (pause,
 * options, Odin's click GUI) vanilla marks its own blur here, at the screen's background stratum,
 * which is later than ours and would blur the HUD too. With "Blur In GUI" on, ours is already in
 * place, so this call is dropped: the screen still blurs, just at the earlier line, and the HUD
 * behind it stays sharp like it does behind a chest.
 *
 * <p>In-world UIs never reach this method at all (vanilla deliberately does not blur them, which is
 * the whole reason the feature exists), so with the setting off nothing here changes.
 */
@Mixin(Screen.class)
public class ScreenBlurMixin {

    @Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
    private void ec$skipVanillaBlurMarker(GuiGraphicsExtractor extractor, CallbackInfo ci) {
        if (RandomStuff.INSTANCE.blursGui()) ci.cancel();
    }
}
