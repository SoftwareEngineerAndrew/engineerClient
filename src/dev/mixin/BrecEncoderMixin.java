package com.devgineerclient.mixin;

import com.devgineerclient.betterrecorder.BrecWire;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Better Recorder: the bytes of every frame the client sends, as the encoder wrote them (packet id and payload, before
 * compression and encryption): where it starts at HEAD, the copy at RETURN. require = 0: a changed target only loses
 * the copy.
 */
@Mixin(PacketEncoder.class)
public abstract class BrecEncoderMixin {

    @Shadow @Final private ProtocolInfo<?> protocolInfo;

    @Unique private int brec$start = -1;

    @Inject(
        method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void brec$outStart(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        brec$start = BrecWire.INSTANCE.getOn() ? out.writerIndex() : -1;
    }

    @Inject(
        method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
        at = @At("RETURN"),
        require = 0,
        expect = 0
    )
    private void brec$out(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        int start = brec$start;
        brec$start = -1;
        if (start < 0) return;
        try {
            BrecWire.INSTANCE.outbound(protocolInfo, packet, out, start);
        } catch (Throwable ignored) {
        }
    }
}
