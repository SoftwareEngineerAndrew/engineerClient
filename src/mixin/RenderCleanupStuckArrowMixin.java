package com.engineerclient.mixin;

import com.engineerclient.skyblock.Arrows;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ArrowLayer.class)
public class RenderCleanupStuckArrowMixin {
    @ModifyReturnValue(method = "numStuck", at = @At("RETURN"))
    private int ec$hideStuckArrows(int stuck) {
        return stuck != 0 && Arrows.INSTANCE.hidesStuckArrows() ? 0 : stuck;
    }
}
