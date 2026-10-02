package com.engineerclient.mixin;

import com.engineerclient.recorder.EffectsCapture;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: every particle that will actually exist, and every tracking emitter (the crit
 * and enchanted-hit bursts that follow an entity for a few ticks).
 *
 * add() is where every particle enters the engine, whether it came through the level (createParticle
 * calls it), a block breaking, or a mod constructing particles itself. The particle's constructor
 * has run by then, so its position, motion and lifetime are set.
 *
 * Optional (require = 0): if a target ever moves, the recorder only loses those rows.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineTapMixin {

    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), require = 0, expect = 0)
    private void ec$particleSpawned(Particle particle, CallbackInfo ci) {
        try {
            EffectsCapture.spawned(particle);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(
        method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void ec$emitter(Entity entity, ParticleOptions options, CallbackInfo ci) {
        try {
            EffectsCapture.emitter(entity, options, -1);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(
        method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;I)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void ec$emitterFor(Entity entity, ParticleOptions options, int lifetime, CallbackInfo ci) {
        try {
            EffectsCapture.emitter(entity, options, lifetime);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }
}
