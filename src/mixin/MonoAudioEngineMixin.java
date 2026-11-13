package com.engineerclient.mixin;

import com.engineerclient.visual.MonoAudio;
import net.minecraft.client.Camera;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public abstract class MonoAudioEngineMixin {
    @Shadow @Final private ChannelAccess channelAccess;

    @Inject(method = "updateSource", at = @At("TAIL"))
    private void ec$refreshMono(Camera camera, CallbackInfo ci) {
        if (!MonoAudio.active()) return;
        channelAccess.executeOnChannels(stream -> stream.forEach(channel -> ((MonoAudio.MonoChannel) channel).ecRefreshMonoPosition()));
    }
}
