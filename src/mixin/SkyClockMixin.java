package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SkyRenderer.class)
public class SkyClockMixin {
    @WrapMethod(method = "extractRenderState")
    private void ec$skyClock(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, Operation<Void> original) {
        long outer = Visibility.skyTime;
        Visibility.skyTime = Visibility.shownTime();
        try {
            original.call(level, partialTicks, camera, state);
        } finally {
            Visibility.skyTime = outer;
        }
    }
}
