package com.engineerclient.mixin.odin;

import com.engineerclient.misc.OdinOnScreen;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Odin's Click GUI panels never off screen (OdinOnScreen): {@code maxY()} is how low a panel's header may sit, for where
 * it is laid out and for dragging it, so raising it keeps part of an open panel's module list on screen too.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.clickgui.ui.PanelKt", remap = false)
public class PanelOnScreenMixin {
    @ModifyReturnValue(method = "maxY", at = @At("RETURN"), remap = false)
    private static int ec$listOnScreen(int original) {
        try {
            return OdinOnScreen.panelMaxY(original);
        } catch (Throwable t) {
            return original;
        }
    }
}
