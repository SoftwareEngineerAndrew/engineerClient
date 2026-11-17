package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractContainerScreen.class)
public class ContainerSlotMixin {
    @Shadow protected Slot hoveredSlot;

    @ModifyExpressionValue(method = {"extractSlotHighlightBack", "extractSlotHighlightFront"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;isHighlightable()Z"))
    private boolean ec$highlight(boolean highlightable) {
        return highlightable && Visibility.highlightsSlot(hoveredSlot);
    }

    @ModifyExpressionValue(method = "extractSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getNoItemIcon()Lnet/minecraft/resources/Identifier;"))
    private Identifier ec$emptyIcon(Identifier icon) {
        return Visibility.emptySlotIcon(icon);
    }
}
