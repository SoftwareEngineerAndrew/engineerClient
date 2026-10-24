package com.coffeeclient.mixin;

import com.coffeeclient.misc.SoundEditor;
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

    /** Random Stuff's Mute Sounds In P4: a new sound isn't started (play reads its volume through the other overload). */
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"), cancellable = true)
    private void cc$muteP4(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (com.coffeeclient.misc.RandomStuff.INSTANCE.mutesSound()) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
    }

    @Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
    private void cc$scaleVolume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        // Mute Sounds In P4, for sounds already playing (only those that tick are asked again: loops, music).
        if (com.coffeeclient.misc.RandomStuff.INSTANCE.mutesSound()) { cir.setReturnValue(0f); return; }
        // Asked for every sound started and again every tick for looping ones: no id string while Sound Editor is off.
        if (!SoundEditor.INSTANCE.getEnabled()) return;
        float m = SoundEditor.multiplier(sound.getIdentifier().toString());
        if (m != 1f) cir.setReturnValue(cir.getReturnValueF() * m);
    }
}
