package com.engineerclient.mixin.odin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.odtheking.odin.utils.skyblock.dungeon.terminals.terminalhandler.TerminalHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Odin 0.3.7's Custom Terminal GUI (TermGui$3: mouse clicks, TermGui$4: key presses, Ctrl for
 * right) hands TerminalHandler.click the button numbered the 26.3 way (SDL: left 1, middle 2,
 * right 3), but the handlers count it the container way (left 0, right 1, middle 2), as the vanilla
 * GUI's slot clicks do. So Rubix clicks were swapped: left counted as right and right as nothing.
 * The button is renumbered on its way in.
 */
@Pseudo
@Mixin(targets = {
    "com.odtheking.odin.features.impl.boss.termGUI.TermGui$3",
    "com.odtheking.odin.features.impl.boss.termGUI.TermGui$4"
}, remap = false)
public class TermGuiButtonMixin {
    @WrapOperation(
        method = "invoke",
        at = @At(value = "INVOKE", target = "Lcom/odtheking/odin/utils/skyblock/dungeon/terminals/terminalhandler/TerminalHandler;click(IIZ)V"),
        remap = false
    )
    private void ec$containerButton(TerminalHandler handler, int slot, int button, boolean simulate, Operation<Void> original) {
        original.call(handler, slot, ec$toContainer(button), simulate);
    }

    @Unique
    private static int ec$toContainer(int sdlButton) {
        return switch (sdlButton) {
            case 1 -> 0;
            case 3 -> 1;
            default -> sdlButton;
        };
    }
}
