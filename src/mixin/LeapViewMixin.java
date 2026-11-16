package com.engineerclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.odtheking.odin.features.impl.dungeon.LeapMenu;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
public abstract class LeapViewMixin {

    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private void ec$leapView(Camera camera, float yRot, float xRot, Operation<Void> original, @Local(argsOnly = true) float partialTicks) {
        float[] look = Minecraft.getInstance().options.getCameraType().isFirstPerson() ? LeapMenu.visualLook(partialTicks) : null;
        if (look == null) original.call(camera, yRot, xRot);
        else original.call(camera, look[0], look[1]);
    }
}
