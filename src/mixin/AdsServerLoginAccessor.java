package com.engineerclient.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** ADS on the server: a proxied login's connection, and the profile the proxy forwarded put in place ([AdsServer]). */
@Mixin(ServerLoginPacketListenerImpl.class)
public interface AdsServerLoginAccessor {
    @Accessor("connection")
    Connection ec$connection();

    @Accessor("authenticatedProfile")
    void ec$setAuthenticatedProfile(GameProfile profile);
}
