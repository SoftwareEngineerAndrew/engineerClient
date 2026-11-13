package com.engineerclient.mixin;

import com.engineerclient.render.ScreenTweaks;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(FogRenderer.class)
public class ScreenTweaksFogMixin {

    @ModifyVariable(method = "getBuffer", at = @At("HEAD"), argsOnly = true)
    private FogRenderer.FogMode ec$noFog(FogRenderer.FogMode mode) {
        return ScreenTweaks.INSTANCE.noFog() ? FogRenderer.FogMode.NONE : mode;
    }
}
