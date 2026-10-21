package net.rustclear.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.SkipPacketDecoderException;
import net.minecraft.network.codec.StreamCodec;
import net.rustclear.mod.engine.HypixelPacketGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A Hypixel Mod API packet that can't be read is skipped rather than disconnecting (see HypixelPacketGuard). */
@Mixin(PacketDecoder.class)
public class HypixelDecoderMixin {
    @WrapOperation(method = "decode", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/codec/StreamCodec;decode(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object rustclear$skipHypixel(StreamCodec<?, ?> codec, Object buf, Operation<Object> original) {
        try {
            return original.call(codec, buf);
        } catch (RuntimeException e) {
            if (!(e instanceof SkipPacketDecoderException) && HypixelPacketGuard.fromHypixel(e)) {
                throw new SkipPacketDecoderException(e);
            }
            throw e;
        }
    }
}
