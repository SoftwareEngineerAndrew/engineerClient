package com.coffeeclient.mixin;

import com.coffeeclient.misc.NoPack;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Pack (NoPack): Hypixel's SkyBlock pack is never downloaded; the server is told it was accepted and loaded. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class NoPackPushMixin {
    @Shadow @Final protected Connection connection;

    @Inject(method = "handleResourcePackPush", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V"))
    private void cc$noPack(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        if (!NoPack.ON || !packet.url().contains("hypixel.net") || !packet.url().contains("SkyBlock")) return;
        connection.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.ACCEPTED));
        connection.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
        ci.cancel();
    }
}
