package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimServer;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ADS: the sim's server never kicks for "Invalid move player packet received" over timing alone. 26.x disconnects a
 * player whose second position packet lands in the same server tick, and one whose teleport accept comes with no
 * teleport waiting. The sim's lag layer holds client packets back and lets them go together, so a teleport (etherwarp's
 * accept plus the client's position right after) did both. In the sim a second position in a tick is taken like the
 * first (as before 26.x) and a stale accept is dropped. NaN or out-of-world values still disconnect.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class SimMoveGuardMixin {
    @Shadow public ServerPlayer player;
    @Shadow private boolean receivedPositionThisTick;
    @Shadow private Vec3 awaitingPositionFromClient;
    @Shadow private int awaitingTeleport;

    private boolean ec$simServer() {
        return SimServer.INSTANCE.getServer() != null && this.player != null && this.player.level().getServer() == SimServer.INSTANCE.getServer();
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void ec$allowSecondMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (ec$simServer()) this.receivedPositionThisTick = false;
    }

    @Inject(method = "handleAcceptTeleportPacket", at = @At("HEAD"), cancellable = true)
    private void ec$dropStaleAccept(ServerboundAcceptTeleportationPacket packet, CallbackInfo ci) {
        if (ec$simServer() && packet.id() == this.awaitingTeleport && this.awaitingPositionFromClient == null) ci.cancel();
    }
}
