package com.bloodrushwaypoints.mixin;

import com.bloodrushwaypoints.rotation.P3Rotation;
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
 * of them. It only reads; it never cancels or modifies, so it cannot affect what they do.
 */
@Mixin(value = Connection.class, priority = 1)
public class ConnectionTapMixin {

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD")
    )
    private void brw$tap(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        P3Rotation.INSTANCE.tap(packet);
    }
}
