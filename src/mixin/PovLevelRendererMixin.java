package com.engineerclient.mixin;

import com.engineerclient.pov.PovCapture;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The terrain upkeep (view area, translucency sort) runs inside {@code LevelRenderer.render}, so a
 * POV pass would run it too. These keep it on the local camera: the view area and the
 * translucency sort are not moved to a teammate's eyes and back every frame. (The
 * occlusion graph is held by the captured frustum, see {@code PovCapture.renderFeed}.)
 */
@Mixin(LevelRenderer.class)
public class PovLevelRendererMixin {

    @Inject(method = "repositionCamera(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
    private void ec$keepViewArea(CameraRenderState camera, CallbackInfo ci) {
        if (PovCapture.INSTANCE.getCapturing()) ci.cancel();
    }

    @WrapWithCondition(
        method = "compileSections(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;scheduleTranslucentSectionResort(Lnet/minecraft/world/phys/Vec3;)V")
    )
    private boolean ec$keepSort(LevelRenderer self, Vec3 cameraPos) {
        return !PovCapture.INSTANCE.getCapturing();
    }
}
