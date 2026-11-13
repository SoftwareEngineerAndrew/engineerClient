package com.engineerclient.mixin;

import com.engineerclient.render.ScreenTweaks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

@Mixin(EffectsInInventory.class)
public class ScreenTweaksInventoryEffectsMixin {

    @Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
    private void ec$hideInventoryEffects(GuiGraphicsExtractor graphics, Collection<MobEffectInstance> effects, int x0, int yStep, int mouseX, int mouseY, int maxWidth, CallbackInfo ci) {
        if (ScreenTweaks.INSTANCE.hideInventoryEffects()) ci.cancel();
    }
}
