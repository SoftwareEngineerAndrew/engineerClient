package com.engineerclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class ScreenTweaksGameRendererMixin {

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void ec$noHurtCamera(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
        if (com.odtheking.odin.features.impl.render.Camera.noHurtCamera()) ci.cancel();
    }
}
