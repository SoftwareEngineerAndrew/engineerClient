package com.engineerclient.mixin;

import com.engineerclient.recorder.WireTap;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: how the game connection ended. Every disconnect (a kick, the server closing the
 * socket, you leaving, an error) passes through disconnect(DisconnectionDetails), and an error is
 * seen first at exceptionCaught. Read only; require = 0 so a changed target never breaks the game.
 */
@Mixin(Connection.class)
public abstract class ConnectionEndTapMixin {

    @Inject(
        method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void ec$disconnect(DisconnectionDetails details, CallbackInfo ci) {
        try {
            WireTap.INSTANCE.disconnected((Connection) (Object) this, details);
        } catch (Throwable ignored) {
        }
    }

    @Inject(
        method = "exceptionCaught(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Throwable;)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void ec$error(ChannelHandlerContext ctx, Throwable error, CallbackInfo ci) {
        try {
            WireTap.INSTANCE.connectionError((Connection) (Object) this, error);
        } catch (Throwable ignored) {
        }
    }
}
