package com.engineerclient.mixin;

import com.mojang.blaze3d.audio.Channel;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OpenAL caps each source at a gain of 1 (AL_MAX_GAIN), whatever its AL_GAIN asks for. For a sound
 * {@link com.engineerclient.misc.SoundEditor} has boosted past 1, the cap is lifted to match; for
 * every other sound it is set back to 1, since channels are pooled and reused.
 */
@Mixin(Channel.class)
public class SoundChannelMixin {

    @Shadow @Final private int source;

    @Inject(method = "setVolume(F)V", at = @At("HEAD"))
    private void ec$liftGainCap(float volume, CallbackInfo ci) {
        AL10.alSourcef(source, AL10.AL_MAX_GAIN, Math.max(1f, volume));
    }
}
