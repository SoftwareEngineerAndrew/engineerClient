package com.engineerclient.mixin;

import com.engineerclient.render.ScreenTweaks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public class ScreenTweaksOverlayMixin {

    @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
    private static void ec$noWaterOverlay(PlayerRenderState.WaterOverlay overlay, PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo ci) {
        if (ScreenTweaks.INSTANCE.noWaterOverlay()) ci.cancel();
    }

    @WrapOperation(method = "submit", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;submitBlockSprite(Lnet/minecraft/resources/Identifier;FFFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void ec$noSuffocationOverlay(Identifier atlas, float u0, float v0, float u1, float v1, PoseStack poseStack, SubmitNodeCollector collector, int color, Operation<Void> original) {
        if (!ScreenTweaks.INSTANCE.noSuffocationOverlay()) original.call(atlas, u0, v0, u1, v1, poseStack, collector, color);
    }
}
