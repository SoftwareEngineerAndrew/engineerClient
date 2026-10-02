package com.engineerclient.mixin;

import com.engineerclient.recorder.WireTap;
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
 * Dungeon Recorder: the exact bytes of every frame the client is about to decode (packet id and
 * payload, after decryption and decompression), for the raw sidecar. Copies the readable bytes
 * without moving the reader index or keeping the buffer; WireTap decides whether the frame belongs
 * to the game connection and is being recorded. require = 0: a changed target only loses the raw
 * bytes, never the game.
 */
@Mixin(PacketDecoder.class)
public abstract class PacketDecoderTapMixin {

    @Shadow @Final private ProtocolInfo<?> protocolInfo;

    @Inject(
        method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void ec$rawIn(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        try {
            WireTap.INSTANCE.decodeFrame(ctx, protocolInfo, in);
        } catch (Throwable ignored) {
        }
    }
}
