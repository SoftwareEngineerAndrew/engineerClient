package com.engineerclient.mixin;

import com.engineerclient.misc.Zpew;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Zpew: while an etherwarp is on its way, the camera is put at its landing (at eye height) instead of on the player. */
@Mixin(Camera.class)
public abstract class ZpewCameraMixin {

    @Shadow private float eyeHeightOld;
    @Shadow private float eyeHeight;

    @Shadow protected abstract void setPosition(Vec3 position);

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void ec$zpewPosition(float partialTicks, CallbackInfo ci) {
        Vec3 pos = Zpew.cameraPos();
        if (pos == null) return;
        setPosition(pos.add(0.0, Mth.lerp(partialTicks, eyeHeightOld, eyeHeight), 0.0));
    }

    // Third person front view isn't mirrored round a camera that isn't on the player.
    @ModifyExpressionValue(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CameraType;isMirrored()Z"))
    private boolean ec$zpewNoMirror(boolean original) {
        return original && Zpew.cameraPos() == null;
    }
}
