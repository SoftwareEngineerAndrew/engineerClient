// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.mixin;

import com.engineerclient.terminals.AdsTerminalSolver;
import com.engineerclient.terminals.CustomTermGui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The terminal solver's custom GUI: drawn instead of the whole terminal window, background and all (CustomTermGui). */
@Mixin(Screen.class)
public abstract class TerminalSolverCustomGuiMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
    private void ec$customTermGui(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (!((Object) this instanceof AbstractContainerScreen<?> screen) || !AdsTerminalSolver.customGuiActive()) return;
        try {
            CustomTermGui.render(screen, graphics, mouseX, mouseY);
            ci.cancel();
        } catch (Throwable t) {
            // the vanilla window, solved or not, beats a blank screen
        }
    }
}
