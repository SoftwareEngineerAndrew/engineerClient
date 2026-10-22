// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.mixin;

import com.engineerclient.terminals.AdsTerminalSolver;
import com.engineerclient.terminals.CustomTermGui;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ADS's terminal solver on a container window (AdsTerminalSolver: only while it has a terminal open, so every
 * other window is left alone): its highlights in place of the slots, the window darkened behind them, no item
 * tooltips, the slot clicks through the solver, and the custom GUI's clicks and keys. Odin's own solver hooks the
 * same methods through its events.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class TerminalSolverScreenMixin {
    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow protected int imageWidth;
    @Shadow protected int imageHeight;

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void ec$termBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        try {
            AdsTerminalSolver.renderBackground((AbstractContainerScreen<?>) (Object) this, graphics, leftPos, topPos, imageWidth, imageHeight);
        } catch (Throwable t) {
            // the window as vanilla draws it
        }
    }

    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void ec$termSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        try {
            if (AdsTerminalSolver.renderSlot((AbstractContainerScreen<?>) (Object) this, graphics, slot)) ci.cancel();
        } catch (Throwable t) {
            // the slot as vanilla draws it
        }
    }

    /** Show Numbers off: the 1-10 stack counts too (the slot being drawn is known at the call: it's the method's own). */
    @WrapOperation(
        method = "extractSlot",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V")
    )
    private void ec$termCount(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y, String countText, Operation<Void> original, GuiGraphicsExtractor g, Slot slot) {
        boolean hide = false;
        try {
            hide = AdsTerminalSolver.hidesCount(slot);
        } catch (Throwable t) {
            // keep the count
        }
        if (!hide) original.call(graphics, font, stack, x, y, countText);
    }

    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V", at = @At("HEAD"), cancellable = true)
    private void ec$termClick(Slot slot, int slotId, int buttonNum, ContainerInput containerInput, CallbackInfo ci) {
        if (AdsTerminalSolver.onSlotClick(slotId, buttonNum)) ci.cancel();
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void ec$termTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (AdsTerminalSolver.hidesTooltip()) ci.cancel();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void ec$customGuiClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (AdsTerminalSolver.customGuiActive() && CustomTermGui.mouseClicked(event)) cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void ec$customGuiKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (AdsTerminalSolver.customGuiActive() && CustomTermGui.keyPressed(event)) cir.setReturnValue(true);
    }
}
