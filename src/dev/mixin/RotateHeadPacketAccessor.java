package com.devgineerclient.mixin;

import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which entity a head-turn packet is for, read on the network thread (see MoveEntityPacketAccessor). */
@Mixin(ClientboundRotateHeadPacket.class)
public interface RotateHeadPacketAccessor {

    @Accessor("entityId")
    int ec_getEntityId();
}
