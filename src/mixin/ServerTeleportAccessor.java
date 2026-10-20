package com.engineerclient.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * ADS replays: the teleport the server waits on the client to accept. A replay accepts it itself before the next recorded move, as the
 * recorded client did within the tick (at a replay's fast tick rate your real client's accept comes many server ticks late, and every
 * move before it is dropped).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerTeleportAccessor {
    @Accessor("awaitingPositionFromClient")
    Vec3 ec_getAwaitingPosition();

    @Accessor("awaitingTeleport")
    int ec_getAwaitingTeleport();
}
