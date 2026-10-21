package net.rustclear.mod.mixin;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.rustclear.mod.engine.SimLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** What the host is sent, for the SimLog (off unless the host turns it on). */
@Mixin(ServerCommonPacketListenerImpl.class)
public class SimLogMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"))
    private void rustclear$simLogSent(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        if (SimLog.enabled() && (Object) this instanceof ServerGamePacketListenerImpl game) {
            SimLog.sent(game.player, packet);
        }
    }
}
