package com.engineerclient.mixin;

import com.engineerclient.visual.Sounds;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SoundEngine.class)
public abstract class MonoListenerMixin {
    @Shadow @Final private ChannelAccess channelAccess;

    // Queued right after the new listener position, so every centred source uses it.
    @WrapOperation(method = "updateSource", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundEngineExecutor;execute(Ljava/lang/Runnable;)V"))
    private void ec$listenerMoved(SoundEngineExecutor executor, Runnable task, Operation<Void> original) {
        original.call(executor, task);
        if (Sounds.monoActive()) channelAccess.executeOnChannels(channels -> channels.forEach(c -> ((Sounds.MonoSource) c).ecRecentre()));
    }
}
