package com.devgineerclient.mixin;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Which entity a relative-move packet is for, read on the network thread: the Boss Recorder's
 * log keeps only the bosses' packets and stamps them with the server tick they arrived on, before
 * the game thread gets to them ({@code getEntity} needs the level, which isn't safe from there).
 */
@Mixin(ClientboundMoveEntityPacket.class)
public interface MoveEntityPacketAccessor {

    @Accessor("entityId")
    int ec_getEntityId();
}
