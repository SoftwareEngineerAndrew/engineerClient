package com.engineerclient.mixin;

import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No potion effect list beside the inventory (the HUD's icons are removed in CleanHud). */
@Mixin(EffectsInInventory.class)
public class HideInventoryEffectsMixin {
    @Inject(method = "canSeeEffects", at = @At("HEAD"), cancellable = true)
    private void ads$hideEffects(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
