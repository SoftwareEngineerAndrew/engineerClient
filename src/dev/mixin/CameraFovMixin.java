package com.devgineerclient.mixin;

import com.devgineerclient.practice.I4Aims;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** i4 Aim's Auto Zoom: narrows the world's FOV (not the hand's) while you look at the i4 board. */
@Mixin(Camera.class)
public class CameraFovMixin {

    @Inject(method = "calculateFov(F)F", at = @At("RETURN"), cancellable = true)
    private void dc$i4Zoom(float partialTick, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(I4Aims.zoomFov(cir.getReturnValueF()));
    }
}
