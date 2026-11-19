package com.engineerclient.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
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
        if (entity == null || !com.adm.features.impl.render.Camera.instantSneak()) return;
        eyeHeight = com.adm.features.impl.render.Camera.legacySneakHeight() && entity.getPose() == Pose.CROUCHING ? 1.54f : entity.getEyeHeight();
        eyeHeightOld = eyeHeight;
    }
}
