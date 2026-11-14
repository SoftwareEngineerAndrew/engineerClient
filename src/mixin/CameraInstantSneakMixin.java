package com.engineerclient.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public class CameraInstantSneakMixin {

    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;
    @Shadow private Entity entity;

    @Inject(method = "tick", at = @At("TAIL"))
    private void ec$instantSneak(CallbackInfo ci) {
        if (entity == null || !com.odtheking.odin.features.impl.render.Camera.instantSneak()) return;
        eyeHeight = entity.getEyeHeight();
        eyeHeightOld = eyeHeight;
    }
}
