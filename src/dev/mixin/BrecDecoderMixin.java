package com.devgineerclient.mixin;

import com.devgineerclient.betterrecorder.BrecWire;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Better Recorder: the bytes of every frame the client is about to decode (packet id and payload, after decryption and
 * decompression), copied without moving the reader index; at RETURN, what it decoded to (for respawns). require = 0: a changed target only loses the copy.
 */
@Mixin(PacketDecoder.class)
public abstract class BrecDecoderMixin {

    @Shadow @Final private ProtocolInfo<?> protocolInfo;

    @Inject(
        method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void brec$in(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        try {
            BrecWire.INSTANCE.inbound(protocolInfo, in);
        } catch (Throwable ignored) {
        }
    }

    @Inject(
        method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V",
        at = @At("RETURN"),
        require = 0,
        expect = 0
    )
    private void brec$decoded(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        try {
            BrecWire.INSTANCE.decoded(out);
        } catch (Throwable ignored) {
        }
    }
}
