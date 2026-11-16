package com.engineerclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.odtheking.odin.features.impl.dungeon.LeapMenu;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
public abstract class LeapViewMixin {

    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private void ec$leapView(Camera camera, float yRot, float xRot, Operation<Void> original, @Local(argsOnly = true) float partialTicks) {
        float[] look = LeapMenu.visualLook(partialTicks);
        LocalPlayer player = Minecraft.getInstance().player;
        if (look == null || player == null) { original.call(camera, yRot, xRot); return; }
        // the third-person front view turns the camera round to face you: kept so
        boolean front = Math.abs(xRot - player.getViewXRot(partialTicks)) > 0.01f || Math.abs(yRot - player.getViewYRot(partialTicks)) > 0.01f;
        if (front) original.call(camera, look[0] + 180f, -look[1]);
        else original.call(camera, look[0], look[1]);
    }
}
