package com.engineerclient.mixin;

import com.engineerclient.render.ScreenTweaks;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Lightmap.class)
public class ScreenTweaksLightmapMixin {

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private LightmapRenderState ec$fullbright(LightmapRenderState state) {
        if (!ScreenTweaks.INSTANCE.fullbright()) return state;
        state.skyFactor = 1f;
        state.blockFactor = 1f;
        state.nightVisionEffectIntensity = 0f;
        state.darknessEffectScale = 0f;
        state.bossOverlayWorldDarkening = 0f;
        state.brightness = 1f;
        state.blockLightTint = LightmapRenderStateExtractor.WHITE;
        state.skyLightColor = LightmapRenderStateExtractor.WHITE;
        state.ambientColor = LightmapRenderStateExtractor.WHITE;
        state.nightVisionColor = LightmapRenderStateExtractor.WHITE;
        return state;
    }
}
