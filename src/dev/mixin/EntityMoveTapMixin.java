package com.devgineerclient.mixin;

import com.devgineerclient.recorder.EntityCapture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionPath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Dungeon Recorder: every move the client applies to an entity through moveOrInterpolateTo. The
 * three other overloads end up in this one; whether the entity then snaps or interpolates is its own
 * choice (items and arrows snap). The handlers' moves that skip it (a far or non-ticking position
 * sync's snapTo, a non-interpolated teleport's setPos) are EntitySnapTapMixin's. Fires thousands of
 * times a second, so the hook returns on a plain flag when the recorder is off and otherwise only
 * stores a few numbers.
 */
@Mixin(Entity.class)
public class EntityMoveTapMixin {

    @Inject(
        method = "moveOrInterpolateTo(Lnet/minecraft/world/entity/PositionPath;FFZ)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void dc$recMove(PositionPath pos, float yRot, float xRot, boolean hasRotation, CallbackInfo ci) {
        if (!EntityCapture.movesOn) return;
        // The recorder must never break the game: whatever it hits stays here.
        try {
            // 26.3: one overload, a nullable path and a rotation flag instead of three Optionals.
            EntityCapture.onMove((Entity) (Object) this, Optional.ofNullable(pos == null ? null : pos.endPosition()),
                hasRotation ? Optional.of(yRot) : Optional.empty(), hasRotation ? Optional.of(xRot) : Optional.empty());
        } catch (Throwable ignored) {
        }
    }
}
