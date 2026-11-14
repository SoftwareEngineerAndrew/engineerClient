package com.engineerclient.mixin;

import com.engineerclient.skyblock.Arrows;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundManager.class)
public abstract class SoundManagerPlayMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void ec$onPlay(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        com.engineerclient.visual.Sounds.recordPlayedSound(instance);
        if (Arrows.onSoundPlay(instance)) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
    }
}
