package com.engineerclient.mixin;

import com.engineerclient.devices.DeviceSolvers;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every packet as it arrives, on the network thread, for the device solvers ({@link DeviceSolvers}): Simon Says'
 * lamp packets are timed by the server tick they came in, and the server's pings count its ticks.
 */
@Mixin(Connection.class)
public class DevicePacketsMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"))
    private void ec$devicePacket(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        DeviceSolvers.onPacket(packet);
    }
}
