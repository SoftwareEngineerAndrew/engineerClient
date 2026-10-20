package com.engineerclient.mixin;

import com.engineerclient.p3sim.clear.replay.ReplayClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An ADS replay's report reads ADS's chat as the packets arrive, before any mod's chat filter can hide a line (a hidden line never
 * reaches Fabric's message events). Taken on the client thread only: the handler runs once on the network thread first.
 */
@Mixin(ClientPacketListener.class)
public abstract class AdsReplayChatMixin {
    @Inject(method = "handleSystemChat", at = @At("HEAD"))
    private void ec$replayChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (ReplayClient.active && !packet.overlay() && Minecraft.getInstance().isSameThread()) ReplayClient.chat(packet.content().getString());
    }
}
