package com.engineerclient.mixin;

import com.engineerclient.ads.world.Game;
import com.engineerclient.p3sim.SimServer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ADS and the boss practice: the sim's server never kicks for "Invalid move player packet received" over timing alone. 26.x disconnects a
 * player whose second position packet lands in the same server tick, and one whose teleport accept comes with no
 * teleport waiting. The sim's lag layer holds client packets back and lets them go together, so a teleport (etherwarp's
 * accept plus the client's position right after) did both. In the sim a second position in a tick is taken like the
 * first (as before 26.x) and a stale accept is dropped. The ADS world gets the same: a client catching up ticks after a hitch
 * (the world finishing loading) sends two positions in one server tick, which kicked within 2 s of joining (2026-10-27). NaN or out-of-world values still disconnect.
 * <p>
 * Nor does it pull you back for "moved wrongly": the sim's world and your client's differ in places (blocks sent to the
 * client only, puzzle and door blocks set a tick apart), and the snap back, sent in the tick of an etherwarp, put you at
 * the old spot and then warped you again (a double etherwarp). Hypixel's worlds match, so it never does that there. The
 * server takes your position as sent; "moved too quickly" still pulls you back.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class SimMoveGuardMixin {
    @Shadow public ServerPlayer player;
    @Shadow private boolean receivedPositionThisTick;
    @Shadow private Vec3 awaitingPositionFromClient;
    @Shadow private int awaitingTeleport;

    /** The boss practice's server or the ADS world's: both are Hypixel's server, which never kicks or snaps back over this. */
    private boolean ec$simServer() {
        if (this.player == null) return false;
        if (Game.isAdsLevel(this.player.level())) return true;
        return SimServer.INSTANCE.getServer() != null && this.player.level().getServer() == SimServer.INSTANCE.getServer();
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void ec$allowSecondMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (ec$simServer()) this.receivedPositionThisTick = false;
    }

    @Inject(method = "handleAcceptTeleportPacket", at = @At("HEAD"), cancellable = true)
    private void ec$dropStaleAccept(ServerboundAcceptTeleportationPacket packet, CallbackInfo ci) {
        if (ec$simServer() && packet.id() == this.awaitingTeleport && this.awaitingPositionFromClient == null) ci.cancel();
    }

    @WrapOperation(method = "handlePlayerPositionChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean ec$noWrongMoveSnap(ServerLevel level, Entity entity, AABB box, Operation<Boolean> original) {
        return !ec$simServer() && original.call(level, entity, box);
    }

    @WrapOperation(method = "handlePlayerPositionChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isEntityCollidingWithAnythingNew(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;DDD)Z"))
    private boolean ec$noNewCollisionSnap(ServerGamePacketListenerImpl self, LevelReader level, Entity entity, AABB box, double x, double y, double z, Operation<Boolean> original) {
        return !ec$simServer() && original.call(self, level, entity, box, x, y, z);
    }
}
