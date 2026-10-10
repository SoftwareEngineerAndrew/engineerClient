package com.devgineerclient.mixin;

import com.devgineerclient.recorder.WireTap;
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
 * Dungeon Recorder: the exact bytes of every frame the client sends, as the encoder wrote them
 * (packet id and payload, before compression and encryption). Where the frame starts is noted at
 * HEAD and the bytes copied at RETURN; an encoder belongs to one channel and encodes one packet at a
 * time on its event loop, so a field is enough. Targets the Packet overload by its full descriptor,
 * not the bridge method. require = 0: a changed target only loses the raw bytes, never the game.
 */
@Mixin(PacketEncoder.class)
public abstract class PacketEncoderTapMixin {

    @Shadow @Final private ProtocolInfo<?> protocolInfo;

    @Unique private int dc$start = -1;

    @Inject(
        method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void dc$rawOutStart(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        try {
            dc$start = WireTap.INSTANCE.wantOut() ? out.writerIndex() : -1;
        } catch (Throwable t) {
            dc$start = -1;
        }
    }

    @Inject(
        method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
        at = @At("RETURN"),
        require = 0,
        expect = 0
    )
    private void dc$rawOut(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        int start = dc$start;
        dc$start = -1;
        if (start < 0) return;
        try {
            WireTap.INSTANCE.encodedFrame(ctx, protocolInfo, packet, out, start);
        } catch (Throwable ignored) {
        }
    }
}
