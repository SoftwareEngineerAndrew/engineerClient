package com.engineerclient.mixin;

import com.engineerclient.render.TimeChanger;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SkyRenderer.class)
public class TimeChangerSkyMixin {

    @WrapMethod(method = "extractRenderState")
    private void ec$skyTime(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, Operation<Void> original) {
        TimeChanger.extractingSky = TimeChanger.INSTANCE.active();
        try {
            original.call(level, partialTicks, camera, state);
        } finally {
            TimeChanger.extractingSky = false;
        }
    }
}
