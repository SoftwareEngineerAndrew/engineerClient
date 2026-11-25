package com.engineerclient.mixin;

import com.engineerclient.skyblock.PingMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class PingMenusPacketMixin {
    // RETURN, not TAIL: an unsigned command returns early.
    @Inject(method = "sendCommand", at = @At("RETURN"))
    private void ec$pmCommand(String command, CallbackInfo ci) {
        PingMenus.onCommand(command);
    }

    @Inject(method = "sendUnattendedCommand", at = @At("RETURN"))
    private void ec$pmUnattended(String command, Screen screen, CallbackInfo ci) {
        PingMenus.onCommand(command);
    }

    @Inject(method = "handleOpenScreen", at = @At("HEAD"), cancellable = true)
    private void ec$pmOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        if (PingMenus.onOpen(packet)) ci.cancel();
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void ec$pmContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        PingMenus.onContent(packet);
    }

    @Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true)
    private void ec$pmClose(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        if (PingMenus.onServerClose()) ci.cancel();
    }
}
