package com.engineerclient.mixin;

import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which entity an entity-event packet (death, hurt...) is for, read on the network thread by Better PF's boss log. */
@Mixin(ClientboundEntityEventPacket.class)
public interface EntityEventPacketAccessor {

    @Accessor("entityId")
    int ec_getEntityId();
}
