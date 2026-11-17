package com.engineerclient.mixin;

import com.adm.features.impl.render.Camera;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

// Mounted, the partial-tick view angles trail the vehicle by a frame; the current angles don't.
@Mixin(net.minecraft.client.Camera.class)
public class CameraRideMixin {
    @Shadow private Entity entity;

    @ModifyExpressionValue(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float ec$rideYaw(float yaw) {
        return Camera.usesLiveRideAngles(entity) ? entity.getYRot() : yaw;
    }

    @ModifyExpressionValue(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
    private float ec$ridePitch(float pitch) {
        return Camera.usesLiveRideAngles(entity) ? entity.getXRot() : pitch;
    }
}
