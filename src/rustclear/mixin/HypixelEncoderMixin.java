package net.rustclear.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.SkipPacketEncoderException;
import net.minecraft.network.codec.StreamCodec;
import net.rustclear.mod.engine.HypixelPacketGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A Hypixel Mod API packet that can't be written is skipped rather than disconnecting (see HypixelPacketGuard). */
@Mixin(PacketEncoder.class)
public class HypixelEncoderMixin {
    @WrapOperation(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/codec/StreamCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void rustclear$skipHypixel(StreamCodec<?, ?> codec, Object buf, Object packet, Operation<Void> original) {
        try {
            original.call(codec, buf, packet);
        } catch (RuntimeException e) {
            if (!(e instanceof SkipPacketEncoderException) && HypixelPacketGuard.fromHypixel(e)) {
                throw new SkipPacketEncoderException(e);
            }
            throw e;
        }
    }
}
