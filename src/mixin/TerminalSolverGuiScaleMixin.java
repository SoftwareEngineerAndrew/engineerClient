// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.mixin;

import com.engineerclient.terminals.AdsTerminalSolver;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The terminal solver's Normal Term Size: the GUI scale while ADS has a terminal open. */
@Mixin(Minecraft.class)
public abstract class TerminalSolverGuiScaleMixin {
    @ModifyExpressionValue(method = "resizeGui", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"))
    private Object ec$termGuiScale(Object original) {
        Integer scale = AdsTerminalSolver.guiScale();
        return scale != null ? scale : original;
    }
}
