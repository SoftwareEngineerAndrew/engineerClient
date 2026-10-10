package com.devgineerclient.mixin;

import com.devgineerclient.recorder.PacketFate;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: when a queued server packet actually takes effect on the game thread
 * ({@link PacketFate}).
 *
 * PacketUtils.ensureRunningOnSameThread wraps each packet bound for the game thread in a
 * ListenerAndPacket (the constructor runs on the network thread, so it also tells channelRead0's
 * tracking the packet was queued, not lost), and PacketProcessor.processQueuedPackets calls handle()
 * on each in turn. handle() has one RETURN: reached after the packet was applied, after
 * shouldHandleMessage refused it (the "Ignoring packet due to disconnection" debug log) or after its
 * handler threw (onPacketError); the last two are caught on their way and mark the packet failed.
 * Every injection is optional (require = 0): if a target moves, the recorder only loses these lines.
 */
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public abstract class PacketApplyTapMixin {

    @Shadow @Final private Packet<?> packet;

    @Inject(
        method = "<init>(Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("RETURN"),
        require = 0,
        expect = 0
    )
    private void dc$queued(PacketListener listener, Packet<?> packet, CallbackInfo ci) {
        try {
            PacketFate.INSTANCE.queued(packet);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(method = "handle()V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$handleStart(CallbackInfo ci) {
        try {
            PacketFate.INSTANCE.handleStart(this.packet);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(
        method = "handle()V",
        at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;debug(Ljava/lang/String;Ljava/lang/Object;)V"),
        require = 0,
        expect = 0
    )
    private void dc$rejected(CallbackInfo ci) {
        try {
            PacketFate.INSTANCE.handleRejected(this.packet);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    /** Sees the exception on its way to onPacketError and hands it on unchanged. */
    @ModifyArg(
        method = "handle()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/PacketListener;onPacketError(Lnet/minecraft/network/protocol/Packet;Ljava/lang/Exception;)V"
        ),
        index = 1,
        require = 0,
        expect = 0
    )
    private Exception dc$error(Exception ex) {
        try {
            PacketFate.INSTANCE.handleError(this.packet, ex);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
        return ex;
    }

    @Inject(method = "handle()V", at = @At("RETURN"), require = 0, expect = 0)
    private void dc$handleEnd(CallbackInfo ci) {
        try {
            PacketFate.INSTANCE.handleEnd(this.packet);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }
}
