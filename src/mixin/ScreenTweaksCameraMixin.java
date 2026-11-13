package com.engineerclient.mixin;

import com.engineerclient.render.ScreenTweaks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// While riding, the interpolated view rotation lags a frame behind; the current one keeps the camera in step.
@Mixin(Camera.class)
public class ScreenTweaksCameraMixin {

    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
    private float ec$ridingPitch(Entity entity, float partialTick, Operation<Float> original) {
        return ScreenTweaks.INSTANCE.fixRidingCamera(entity) ? entity.getXRot() : original.call(entity, partialTick);
    }

    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float ec$ridingYaw(Entity entity, float partialTick, Operation<Float> original) {
        return ScreenTweaks.INSTANCE.fixRidingCamera(entity) ? entity.getYRot() : original.call(entity, partialTick);
    }
}
