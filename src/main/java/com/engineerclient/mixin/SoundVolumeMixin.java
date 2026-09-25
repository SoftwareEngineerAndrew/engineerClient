package com.engineerclient.mixin;

import com.engineerclient.misc.SoundEditor;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link SoundEditor}'s volume: scales a sound's final volume after Minecraft has capped it at 1,
 * so a multiplier above 1 is not undone. {@link SoundChannelMixin} lifts OpenAL's own cap.
 */
@Mixin(SoundEngine.class)
public class SoundVolumeMixin {

    @Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
    private void ec$scaleVolume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        float m = SoundEditor.multiplier(sound.getIdentifier().toString());
        if (m != 1f) cir.setReturnValue(cir.getReturnValueF() * m);
    }
}
