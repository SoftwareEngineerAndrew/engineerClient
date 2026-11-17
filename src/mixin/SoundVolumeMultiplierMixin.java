package com.engineerclient.mixin;

import com.engineerclient.visual.Sounds;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractSoundInstance.class)
public abstract class SoundVolumeMultiplierMixin {
    @ModifyReturnValue(method = "getVolume", at = @At("RETURN"))
    private float ec$volumeMultiplier(float volume) {
        return Sounds.scaleVolume((AbstractSoundInstance) (Object) this, volume);
    }
}
