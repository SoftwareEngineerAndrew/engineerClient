package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;

@Mixin(EffectsInInventory.class)
public class ScreenTweaksInventoryEffectsMixin {

    @WrapWithCondition(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/EffectsInInventory;extractEffects(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/Collection;IIIII)V"))
    private boolean ec$drawEffects(EffectsInInventory self, GuiGraphicsExtractor graphics, Collection<MobEffectInstance> effects, int x0, int yStep, int mouseX, int mouseY, int maxWidth) {
        return !Visibility.INSTANCE.hideInventoryEffects();
    }

    /** With the list hidden the inventory stays centred instead of making room for it. */
    @ModifyReturnValue(method = "canSeeEffects", at = @At("RETURN"))
    private boolean ec$effectsRoom(boolean original) {
        return original && !Visibility.INSTANCE.hideInventoryEffects();
    }
}
