package com.engineerclient.mixin;

import com.engineerclient.visual.Sounds;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Channel.class)
public abstract class MonoAudioChannelMixin implements Sounds.MonoChannel {
    @Shadow @Final private int source;
    @Unique private Vec3 ec$lastPosition = Vec3.ZERO;
    @Unique private boolean ec$relative;

    @Inject(method = "setSelfPosition", at = @At("HEAD"), cancellable = true)
    private void ec$monoPosition(Vec3 pos, CallbackInfo ci) {
        if (!Sounds.monoActive()) return;
        ec$lastPosition = pos;
        ecRefreshMonoPosition();
        ci.cancel();
    }

    @Inject(method = "setRelative", at = @At("HEAD"), cancellable = true)
    private void ec$monoRelative(boolean relative, CallbackInfo ci) {
        if (!Sounds.monoActive()) return;
        ec$relative = relative;
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        ecRefreshMonoPosition();
        ci.cancel();
    }

    @Override
    public void ecRefreshMonoPosition() {
        if (!Sounds.monoActive()) return;
        Sounds.applyCenteredPosition(source, ec$relative ? ec$lastPosition.length() : Sounds.distanceToListener(ec$lastPosition));
    }
}
