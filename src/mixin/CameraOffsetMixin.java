package com.engineerclient.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraOffsetMixin {

    @Shadow private boolean detached;
    @Shadow protected abstract void move(float forward, float up, float left);

    @Inject(method = "alignWithEntity", at = @At("RETURN"))
    private void ec$offset(float partialTicks, CallbackInfo ci) {
        if (detached) return;
        float offset = com.adm.features.impl.render.Camera.offsetBlocks();
        if (offset != 0f) move(offset, 0f, 0f);
    }
}
