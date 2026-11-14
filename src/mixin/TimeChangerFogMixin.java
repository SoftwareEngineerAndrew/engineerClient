package com.engineerclient.mixin;

import com.engineerclient.render.ScreenTweaks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AtmosphericFogEnvironment.class)
public class TimeChangerFogMixin {

    @WrapMethod(method = "getBaseColor")
    private Vector3fc ec$fogTime(ClientLevel level, Camera camera, int renderDistance, float partialTicks, Operation<Vector3fc> original) {
        ScreenTweaks.extractingSky = ScreenTweaks.INSTANCE.changesTime();
        try {
            return original.call(level, camera, renderDistance, partialTicks);
        } finally {
            ScreenTweaks.extractingSky = false;
        }
    }
}
