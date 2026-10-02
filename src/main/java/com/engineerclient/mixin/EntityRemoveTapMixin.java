package com.engineerclient.mixin;

import com.engineerclient.recorder.EntityCapture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: an entity removed from the client level by id (remove_entities and the like),
 * with the reason, while it can still be looked up. Fabric's unload event that follows is the same
 * removal and is not written twice.
 */
@Mixin(ClientLevel.class)
public class EntityRemoveTapMixin {

    @Inject(
        method = "removeEntity(ILnet/minecraft/world/entity/Entity$RemovalReason;)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void ec$recRemove(int id, Entity.RemovalReason reason, CallbackInfo ci) {
        EntityCapture.onLevelRemove((ClientLevel) (Object) this, id, reason);
    }
}
