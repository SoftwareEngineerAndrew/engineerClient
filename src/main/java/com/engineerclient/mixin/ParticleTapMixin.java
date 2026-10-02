package com.engineerclient.mixin;

import com.engineerclient.recorder.EffectsCapture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: every particle the level is asked for. All four public addParticle /
 * addAlwaysVisibleParticle overloads funnel into the private doAddParticle, which then applies the
 * Particles option and the distance cut-off before creating anything. HEAD records the request,
 * RETURN how many particles it actually made (counted by ParticleEngineTapMixin in between), so the
 * filtered-away ones are visible.
 *
 * Optional (require = 0): if the target ever moves, the recorder only loses the req rows.
 */
@Mixin(ClientLevel.class)
public abstract class ParticleTapMixin {

    @Inject(
        method = "doAddParticle(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void ec$particleRequested(ParticleOptions options, boolean force, boolean alwaysShow, double x, double y, double z,
                                      double dx, double dy, double dz, CallbackInfo ci) {
        try {
            EffectsCapture.requested(options, force, alwaysShow, x, y, z, dx, dy, dz);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(
        method = "doAddParticle(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V",
        at = @At("RETURN"),
        require = 0,
        expect = 0
    )
    private void ec$particleRequestDone(ParticleOptions options, boolean force, boolean alwaysShow, double x, double y, double z,
                                        double dx, double dy, double dz, CallbackInfo ci) {
        try {
            EffectsCapture.requestDone();
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }
}
