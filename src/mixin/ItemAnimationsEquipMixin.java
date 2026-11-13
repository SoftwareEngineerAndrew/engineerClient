package com.engineerclient.mixin;

import com.engineerclient.render.ItemAnimations;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FirstPersonHandsAndItems.class)
public class ItemAnimationsEquipMixin {
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"))
    private float ec$holdHeight(float scale) {
        return ItemAnimations.holdsHeight() ? 1f : scale;
    }
}
