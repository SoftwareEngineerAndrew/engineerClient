package com.coffeeclient.mixin;

import com.coffeeclient.misc.CameraOffset;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Camera Offset: once the camera is at your eyes and facing your way, moved along that way (first person only). */
@Mixin(Camera.class)
public abstract class CameraOffsetMixin {

    @Shadow public abstract boolean isDetached();
    @Shadow protected abstract void move(float zoom, float dy, float dx);

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void cc$cameraOffset(float partialTicks, CallbackInfo ci) {
        float blocks = CameraOffset.blocks();
        if (blocks != 0f && !isDetached()) move(blocks, 0f, 0f);
    }
}
