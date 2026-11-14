package com.engineerclient.mixin;

import com.engineerclient.visual.Sounds;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractSoundInstance.class)
public abstract class SoundVolumeMultiplierMixin {
    @Inject(method = "getVolume", at = @At("RETURN"), cancellable = true)
    private void ec$volumeMultiplier(CallbackInfoReturnable<Float> cir) {
        float multiplier = Sounds.getMultiplier(((AbstractSoundInstance) (Object) this).getIdentifier().toString());
        if (multiplier != 1f) cir.setReturnValue(cir.getReturnValueF() * multiplier);
    }
}
