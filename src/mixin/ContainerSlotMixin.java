package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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

    @WrapOperation(method = "extractSlot", at = {
        @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;item(Lnet/minecraft/world/item/ItemStack;III)V"),
        @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fakeItem(Lnet/minecraft/world/item/ItemStack;III)V")
    })
    private void ec$rarity(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, int seed, Operation<Void> original) {
        Visibility.drawRarity(graphics, stack, x, y, false);
        original.call(graphics, stack, x, y, seed);
    }
}
