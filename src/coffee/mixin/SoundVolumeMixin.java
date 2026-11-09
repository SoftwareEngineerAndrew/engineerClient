package com.coffeeclient.mixin;

import com.coffeeclient.misc.SoundEditor;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sounds that don't play: Random Stuff's Mute Sounds In P4 and {@link SoundEditor}'s mute list. A new one isn't started; one
 * already playing (only those that tick are asked again: loops, music) goes silent.
 */
@Mixin(SoundEngine.class)
public class SoundVolumeMixin {

    /** Random Stuff's Mute Sounds In P4: a new sound isn't started (play reads its volume through the other overload). */
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"), cancellable = true)
    private void cc$muteP4(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (com.coffeeclient.misc.RandomStuff.INSTANCE.mutesSound() || SoundEditor.mutes(sound.getIdentifier().toString())) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
    }

    @Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
    private void cc$scaleVolume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        if (com.coffeeclient.misc.RandomStuff.INSTANCE.mutesSound() || SoundEditor.mutes(sound.getIdentifier().toString())) cir.setReturnValue(0f);
    }
}
