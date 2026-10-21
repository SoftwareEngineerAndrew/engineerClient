package net.rustclear.mod.mixin;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.rustclear.mod.engine.SimLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The host's clicks and closes, on the server thread (after the hop off the network thread). */
@Mixin(ServerGamePacketListenerImpl.class)
public class SimLogReceiveMixin {
    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"))
    private void rustclear$simLogClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (SimLog.enabled()) {
            SimLog.received(((ServerGamePacketListenerImpl) (Object) this).player, packet);
        }
    }

    @Inject(method = "handleContainerClose", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"))
    private void rustclear$simLogClose(ServerboundContainerClosePacket packet, CallbackInfo ci) {
        if (SimLog.enabled()) {
            SimLog.received(((ServerGamePacketListenerImpl) (Object) this).player, packet);
        }
    }
}
