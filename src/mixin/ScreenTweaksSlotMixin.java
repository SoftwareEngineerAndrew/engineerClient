package com.engineerclient.mixin;

import com.engineerclient.render.ScreenTweaks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public class ScreenTweaksSlotMixin {

    @Shadow protected Slot hoveredSlot;

    @Inject(method = "extractSlotHighlightBack", at = @At("HEAD"), cancellable = true)
    private void ec$noPaneHighlightBack(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (ScreenTweaks.INSTANCE.hidesPaneHighlight(hoveredSlot)) ci.cancel();
    }

    @Inject(method = "extractSlotHighlightFront", at = @At("HEAD"), cancellable = true)
    private void ec$noPaneHighlightFront(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (ScreenTweaks.INSTANCE.hidesPaneHighlight(hoveredSlot)) ci.cancel();
    }

    @ModifyExpressionValue(method = "extractSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getNoItemIcon()Lnet/minecraft/resources/Identifier;"))
    private Identifier ec$hideOffhandBackground(Identifier icon) {
        return ScreenTweaks.INSTANCE.slotIcon(icon);
    }
}
