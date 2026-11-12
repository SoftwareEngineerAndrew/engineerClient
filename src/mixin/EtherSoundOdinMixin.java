package com.engineerclient.mixin;

import com.engineerclient.misc.EtherSound;
import com.odtheking.odin.events.PacketEvent;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0 Ping Etherwarp Sound: Odin's Etherwarp module swaps the server's etherwarp sound for its own as the packet arrives; for a
 * warp the camera already played, the packet is dropped before Odin plays it again.
 */
@Mixin(targets = "com.odtheking.odin.features.impl.render.Etherwarp$1", remap = false)
public abstract class EtherSoundOdinMixin {
    @Inject(method = "invoke(Lnet/minecraft/network/protocol/game/ClientboundSoundPacket;Lcom/odtheking/odin/events/PacketEvent$Receive;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void ec$etherSound(ClientboundSoundPacket packet, PacketEvent.Receive event, CallbackInfo ci) {
        try {
            if (EtherSound.swallow(packet)) {
                event.cancel();
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }
}
