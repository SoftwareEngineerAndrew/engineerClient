package com.devgineerclient.mixin;

import com.devgineerclient.recorder.PacketFate;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: how far each server packet got through channelRead0 ({@link PacketFate}).
 *
 * ConnectionTapMixin sees every packet first, at HEAD. Other mods cancel packets with their own
 * callbacks: at HEAD (before the method's own code runs) or at the genericsFtw call, after the
 * vanilla checks (Odin 0.3.4 cancels there, through its bus). So this marks the method's own calls
 * instead of trusting a RETURN alone: Channel.isOpen (the first call of the body: no HEAD callback
 * cancelled it), genericsFtw (handed to the listener, run on this thread or queued for the game
 * thread) and RETURN; ConnectionTapMixin also marks genericsFtw first of all (priority 1, "passed").
 * A packet whose read never reached the body was cancelled at HEAD; one that passed the checks but
 * never reached this genericsFtw mark was cancelled there; one that reached the body but never
 * passed was rejected (closed channel, or shouldHandleMessage false). Priority 2000 puts these after
 * every default-priority mixin, so a callback inserted at the same call runs before this mark;
 * every injection is optional (require = 0), and PacketFate does not read a missing mark as a
 * cancel until the marks have been seen working.
 */
@Mixin(value = Connection.class, priority = 2000)
public class ConnectionReadEndMixin {

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(value = "INVOKE", target = "Lio/netty/channel/Channel;isOpen()Z", ordinal = 0),
        require = 0,
        expect = 0
    )
    private void dc$readBody(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        try {
            PacketFate.INSTANCE.readStage(packet, PacketFate.STAGE_BODY);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"
        ),
        require = 0,
        expect = 0
    )
    private void dc$readDispatched(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        try {
            PacketFate.INSTANCE.readStage(packet, PacketFate.STAGE_DISPATCHED);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("RETURN"),
        require = 0,
        expect = 0
    )
    private void dc$readEnd(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        try {
            PacketFate.INSTANCE.readEnd(packet);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }
}
