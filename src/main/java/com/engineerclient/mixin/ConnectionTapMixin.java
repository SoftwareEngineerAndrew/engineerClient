package com.engineerclient.mixin;

import com.engineerclient.bossrecorder.BossRecorder;
import com.engineerclient.recorder.DungeonRecorder;
import com.engineerclient.recorder.PacketFate;
import com.engineerclient.rotation.P3Rotation;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First look at every inbound packet, before any other mod's hook on this method.
 *
 * Several dungeon mods (blade-addons, devonian, Odin itself) inject into {@code channelRead0} to
 * rewrite or drop system chat — terminal-completion lines in particular — and whichever runs first
 * and cancels hides the packet from everyone after it. Priority 1 puts this callback ahead of all
 * of them. The Boss Recorder reads every packet here too, for the same reason. It only reads; it never cancels or modifies, so it cannot affect what they do.
 */
@Mixin(value = Connection.class, priority = 1)
public class ConnectionTapMixin {

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD")
    )
    private void ec$tap(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        P3Rotation.INSTANCE.tap(packet);
        BossRecorder.INSTANCE.tap(packet);
        DungeonRecorder.INSTANCE.tap((Connection) (Object) this, packet);
    }

    /**
     * Dungeon Recorder: the packet passed the vanilla checks and is about to be handed on. Priority 1
     * puts this ahead of any mod that cancels at the same call (Odin does), so PacketFate can tell
     * such a cancel from a vanilla rejection.
     */
    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"
        ),
        require = 0,
        expect = 0
    )
    private void ec$readPassed(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        try {
            PacketFate.INSTANCE.readStage(packet, PacketFate.STAGE_PASSED);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }
}
