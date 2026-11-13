package com.engineerclient.mixin;

import com.engineerclient.render.NoItemResetOnSwap;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FirstPersonHandsAndItems.class)
public class NoItemResetMixin {
    @ModifyReturnValue(method = "shouldInstantlyReplaceVisibleItem", at = @At("RETURN"))
    private boolean ec$instantSwap(boolean instant) {
        return instant || NoItemResetOnSwap.active();
    }

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"))
    private float ec$noSwapDip(float scale) {
        return NoItemResetOnSwap.active() ? 1f : scale;
    }
}
