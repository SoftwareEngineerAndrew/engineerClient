package com.engineerclient.mixin;

import com.engineerclient.p3sim.P3Sim;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The P3 Sim's half of Odin's Hide Players: the sim's party are mannequins, not players, so
 * Odin's rule never sees them. With the sim's Hide players on, {@link P3Sim#hideBot} applies the
 * same rule (Hide all, or within Distance) to them. Nothing outside the sim.
 */
@Mixin(EntityRenderDispatcher.class)
public class HideBotsSimMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void ec$hideSimBots(E entity, Frustum frustum, double x, double y, double z, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
        if (P3Sim.hideBot(entity)) cir.setReturnValue(false);
    }
}
