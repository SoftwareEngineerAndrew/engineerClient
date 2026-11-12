package com.engineerclient.mixin;

import com.engineerclient.misc.EtherSound;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 0 Ping Etherwarp Sound: the server's etherwarp sound is not played again when the camera's warp already played it. */
@Mixin(ClientPacketListener.class)
public abstract class EtherSoundMixin {
    @Inject(method = "handleSoundEvent", at = @At("HEAD"), cancellable = true)
    private void ec$etherSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        try {
            if (EtherSound.swallow(packet)) ci.cancel();
        } catch (Throwable ignored) {
        }
    }
}
