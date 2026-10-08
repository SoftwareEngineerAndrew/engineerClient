package com.devgineerclient.mixin;

import com.devgineerclient.recorder.EffectsCapture;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dungeon Recorder: every sound the engine is asked to play, with how it went, and every stop.
 *
 * play() is the one funnel for the server's sounds, the client's own and every mod's (SoundManager
 * and the delayed queue both end here). Its RETURN sees each outcome, including the early
 * NOT_STARTED returns (sound off, unknown event, empty sound) that never reach the engine's
 * listeners. The stops are hooked here rather than on SoundManager (which only delegates) so the
 * engine's own stops (a tickable sound that asked to stop, each instance a stop(id, source)
 * matched) are kept too.
 *
 * Optional (require = 0): if a target ever moves, the recorder only loses these lines. Every body
 * returns at once inside EffectsCapture when nothing is recording.
 */
@Mixin(SoundEngine.class)
public abstract class SoundPlayTapMixin {

    @Inject(
        method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
        at = @At("RETURN"),
        require = 0,
        expect = 0
    )
    private void dc$soundPlayed(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        try {
            EffectsCapture.played(instance, cir.getReturnValue());
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(
        method = "stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void dc$soundStopped(SoundInstance instance, CallbackInfo ci) {
        try {
            if (!EffectsCapture.recordingSounds()) return;
            EffectsCapture.stopped(instance, instance != null && ((SoundEngine) (Object) this).isActive(instance));
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(
        method = "stop(Lnet/minecraft/resources/Identifier;Lnet/minecraft/sounds/SoundSource;)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void dc$soundsStopped(Identifier id, SoundSource source, CallbackInfo ci) {
        try {
            EffectsCapture.stoppedMatching(id, source);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(method = "stopAll()V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$allSoundsStopped(CallbackInfo ci) {
        try {
            EffectsCapture.stoppedAll();
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }
}
