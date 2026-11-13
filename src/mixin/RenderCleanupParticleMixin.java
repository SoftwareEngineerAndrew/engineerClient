package com.engineerclient.mixin;

import com.engineerclient.render.RenderCleanup;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public class RenderCleanupParticleMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void ec$hideParticle(Particle particle, CallbackInfo ci) {
        if (RenderCleanup.INSTANCE.hidesParticle(particle)) ci.cancel();
    }
}
