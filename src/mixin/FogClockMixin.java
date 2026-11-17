package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AtmosphericFogEnvironment.class)
public class FogClockMixin {
    @WrapMethod(method = "getBaseColor")
    private Vector3fc ec$fogClock(ClientLevel level, Camera camera, int renderDistance, float partialTicks, Operation<Vector3fc> original) {
        long outer = Visibility.skyTime;
        Visibility.skyTime = Visibility.shownTime();
        try {
            return original.call(level, camera, renderDistance, partialTicks);
        } finally {
            Visibility.skyTime = outer;
        }
    }
}
