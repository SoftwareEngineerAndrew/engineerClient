package com.devgineerclient.mixin;

import com.devgineerclient.posepov.PosePov;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pose POV draws its feeds here, after the world and before the GUI that shows them (see PosePov.renderQueued). */
@Mixin(GameRenderer.class)
public class PosePovRenderMixin {
    @Inject(method = "renderLevel()V", at = @At("RETURN"))
    private void dc$renderPosePov(CallbackInfo ci) {
        PosePov.INSTANCE.renderQueued();
    }
}
