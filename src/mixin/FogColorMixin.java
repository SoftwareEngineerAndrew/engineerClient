package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link RandomStuff}'s "Black Sky". computeFogColor writes the colour the frame is cleared to and
 * distant terrain fades into, so blacking it out makes the whole sky black (and fog fade to black
 * to match). Only in open air: underwater/lava/powder snow keep their own colour so you can still
 * tell where you are.
 */
@Mixin(FogRenderer.class)
public class FogColorMixin {

    @Inject(method = "computeFogColor", at = @At("TAIL"))
    private void ec$blackSky(Camera camera, float partialTick, ClientLevel level, int renderDistance, float darkenAmount, Vector4f dest, CallbackInfo ci) {
        if (!RandomStuff.INSTANCE.blackSkyActive() || camera.getFluidInCamera() != FogType.NONE) return;
        dest.set(0f, 0f, 0f, 1f);
    }
}
