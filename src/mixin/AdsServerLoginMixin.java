package com.engineerclient.mixin;

import com.engineerclient.p3sim.AdsServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ADS on the server: while your sim is listed there, everyone else comes in through the ADS proxy, which has
 * already checked their account with Mojang and forwards who they are ([AdsServer] reads that). So their login
 * here skips the game's own Mojang check (the encryption request), as on any server behind a Velocity proxy.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public class AdsServerLoginMixin {
    @Redirect(method = "handleHello", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"))
    private boolean ec$proxyChecksAccounts(MinecraftServer server) {
        return !AdsServer.forwards(server) && server.usesAuthentication();
    }
}
