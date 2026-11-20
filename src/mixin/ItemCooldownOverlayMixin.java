package com.engineerclient.mixin;

import com.engineerclient.skyblock.ItemTweaks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiGraphicsExtractor.class)
public class ItemCooldownOverlayMixin {
    @ModifyExpressionValue(method = "itemCooldown", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemCooldowns;getCooldownPercent(Lnet/minecraft/world/item/ItemStack;F)F"))
    private float ec$hideCooldown(float percent) {
        return ItemTweaks.hidesCooldowns() ? 0f : percent;
    }
}
