package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

@Mixin(EffectsInInventory.class)
public class ScreenTweaksInventoryEffectsMixin {

    @Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
    private void ec$hideInventoryEffects(GuiGraphicsExtractor graphics, Collection<MobEffectInstance> effects, int x0, int yStep, int mouseX, int mouseY, int maxWidth, CallbackInfo ci) {
        if (Visibility.INSTANCE.hideInventoryEffects()) ci.cancel();
    }

    /** With the list hidden the inventory stays centred instead of making room for it. */
    @Inject(method = "canSeeEffects", at = @At("HEAD"), cancellable = true)
    private void ec$noEffectsRoom(CallbackInfoReturnable<Boolean> cir) {
        if (Visibility.INSTANCE.hideInventoryEffects()) cir.setReturnValue(false);
    }
}
