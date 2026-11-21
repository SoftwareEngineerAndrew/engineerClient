package com.adm.mixin.mixins;

import com.adm.events.core.EventBus;
import com.adm.events.EntityEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(
            method = "moveOrInterpolateTo(Lnet/minecraft/world/entity/PositionPath;FFZ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/InterpolationHandler;interpolateTo(Lnet/minecraft/world/entity/PositionPath;FFZ)Z", shift = At.Shift.AFTER)
    )
    private void onMoveOrInterpolateTo(PositionPath position, float yRot, float xRot, boolean hasRotation, CallbackInfo ci) {
        if (!EventBus.hasListeners(EntityEvent.Move.class)) return;
        Entity entity = (Entity)(Object)this;
        Vec3 newPos = position != null ? position.endPosition() : Vec3.ZERO;
        new EntityEvent.Move(entity, newPos, yRot, xRot, entity.onGround()).postAndCatch();
    }
}
