package com.devgineerclient.mixin;

import com.devgineerclient.misc.EarlyJoin;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Early join: the switch from configuration to play waits for the first resource load (EarlyJoin). */
@Mixin(ClientConfigurationPacketListenerImpl.class)
public class EarlyJoinHoldMixin {

    @Inject(
        method = "handleConfigurationFinished",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void dc$holdUntilLoaded(ClientboundFinishConfigurationPacket packet, CallbackInfo ci) {
        if (EarlyJoin.hold((ClientConfigurationPacketListenerImpl) (Object) this, packet)) ci.cancel();
    }
}
