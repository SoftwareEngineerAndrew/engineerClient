package com.engineerclient.mixin;

import com.engineerclient.skyblock.Arrows;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ArrowLayer.class)
public class RenderCleanupStuckArrowMixin {
    @Inject(method = "numStuck", at = @At("HEAD"), cancellable = true)
    private void ec$hideStuckArrows(AvatarRenderState state, CallbackInfoReturnable<Integer> cir) {
        if (Arrows.INSTANCE.hidesStuckArrows()) cir.setReturnValue(0);
    }
}
