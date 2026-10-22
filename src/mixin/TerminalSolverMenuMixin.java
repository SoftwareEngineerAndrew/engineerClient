// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.mixin;

import com.engineerclient.terminals.AdsTerminalSolver;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Each slot the server sends to a window (setItem: the per-slot update; a full refill goes through
 * initializeContents instead and, as in Odin, is not solved on), for ADS's terminal solver to solve again.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class TerminalSolverMenuMixin {
    @Inject(method = "setItem", at = @At("TAIL"))
    private void ec$termSlot(int slot, int stateId, ItemStack itemStack, CallbackInfo ci) {
        try {
            AdsTerminalSolver.onSetItem((AbstractContainerMenu) (Object) this, slot, itemStack);
        } catch (Throwable t) {
            com.engineerclient.EngineerClient.INSTANCE.getLogger().warn("[ads] terminal solver update failed", t);
        }
    }
}
