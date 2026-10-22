package com.engineerclient.mixin;

import com.engineerclient.ads.splits.AdsSplitsManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ADS's splits read chat straight off the packet, on the client thread (after the handler's hop off the network
 * thread), before any mod's chat event can hide or rewrite a line: chat cleaners drop exactly the boss lines the
 * splits are timed from. Read only; outside ADS's worlds {@link AdsSplitsManager} ignores it.
 */
@Mixin(ClientPacketListener.class)
public class AdsSplitsChatMixin {
    @Inject(method = "handleSystemChat", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER))
    private void ec$adsSplitsChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (!packet.overlay()) AdsSplitsManager.onChat(packet.content());
    }
}
