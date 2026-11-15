package com.engineerclient.mixin;

import com.engineerclient.tpmaze.TeamTpMaze;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Team TP Maze: our own teleport, read before the game moves us (the first call, on the network thread, only hands it over). */
@Mixin(ClientPacketListener.class)
public abstract class TpMazeTeleportMixin {
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void ec$tpMazeTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        try {
            TeamTpMaze.ownTeleport(packet);
        } catch (Throwable ignored) {
        }
    }
}
