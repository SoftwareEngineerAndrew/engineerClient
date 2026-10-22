// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.mixin;

import com.engineerclient.terminals.AdsTerminalSolver;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A screen put up or closed: ADS's terminal solver looks at whether it's a terminal. */
@Mixin(Gui.class)
public abstract class TerminalSolverOpenMixin {
    @Inject(method = "setScreen", at = @At("TAIL"))
    private void ec$termOpen(Screen screen, CallbackInfo ci) {
        try {
            AdsTerminalSolver.onScreenSet(screen);
        } catch (Throwable t) {
            com.engineerclient.EngineerClient.INSTANCE.getLogger().warn("[ads] terminal solver open failed", t);
        }
    }
}
