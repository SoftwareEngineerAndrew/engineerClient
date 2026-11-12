package com.engineerclient.mixin;

import com.engineerclient.misc.Zpew;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zpew: a server teleport, the moment the game has moved you (game thread, same frame), so the camera's hand-off never
 * shows a frame at your old spot.
 */
@Mixin(ClientPacketListener.class)
public abstract class ZpewTeleportMixin {

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void ec$zpewBeforeTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !Minecraft.getInstance().isSameThread()) return;
        try {
            Zpew.beforeTeleport(p.getX(), p.getY(), p.getZ());
        } catch (Throwable ignored) {
            // Never let the camera break teleports.
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void ec$zpewTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        try {
            Zpew.onTeleportApplied(p.getX(), p.getY(), p.getZ());
        } catch (Throwable ignored) {
            // Never let the camera break teleports.
        }
    }
}
