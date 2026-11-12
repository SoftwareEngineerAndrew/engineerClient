package com.engineerclient.mixin;

import com.engineerclient.misc.Zpew;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Legit Zpew: while teleports are on their way, the camera starts from the last one's landing (eye height), not the
 * player. Swapped in where the game places the camera, so third person still pulls back (and mirrors) from there.
 */
@Mixin(Camera.class)
public abstract class ZpewCameraMixin {

    @Shadow private float eyeHeightOld;
    @Shadow private float eyeHeight;

    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"))
    private void ec$zpewPosition(Camera camera, double x, double y, double z, Operation<Void> original, @Local(argsOnly = true) float partialTicks) {
        Vec3 pos = Zpew.cameraPos();
        if (pos == null) original.call(camera, x, y, z);
        else original.call(camera, pos.x, pos.y + Mth.lerp(partialTicks, eyeHeightOld, eyeHeight), pos.z);
    }
}
