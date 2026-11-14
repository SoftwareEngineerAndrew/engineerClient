package com.engineerclient.mixin;

import com.engineerclient.render.RenderCleanup;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class RenderCleanupFireMixin<T extends Entity, S extends EntityRenderState> {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void ec$hideEntityFire(T entity, S state, float partialTicks, CallbackInfo ci) {
        if (RenderCleanup.INSTANCE.hidesEntityFire()) state.displayFireAnimation = false;
    }
}
