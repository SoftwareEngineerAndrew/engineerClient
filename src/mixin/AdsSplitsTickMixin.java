package com.engineerclient.mixin;

import com.engineerclient.ads.splits.AdsSplitsManager;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server's own clock for ADS's splits' tick times: Hypixel pings every client each server tick, and so do the
 * P3 Sim and Rustclear worlds (Odin counts the same packet as its server tick). Client thread only.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class AdsSplitsTickMixin {
    @Inject(method = "handlePing", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER))
    private void ec$adsSplitsTick(ClientboundPingPacket packet, CallbackInfo ci) {
        AdsSplitsManager.onServerTick();
    }
}
