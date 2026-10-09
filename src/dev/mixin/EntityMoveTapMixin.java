package com.devgineerclient.mixin;

import com.devgineerclient.recorder.EntityCapture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
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
        method = "moveOrInterpolateTo(Ljava/util/Optional;Ljava/util/Optional;Ljava/util/Optional;)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void dc$recMove(Optional<Vec3> pos, Optional<Float> yRot, Optional<Float> xRot, CallbackInfo ci) {
        if (!EntityCapture.movesOn) return;
        // The recorder must never break the game: whatever it hits stays here.
        try {
            EntityCapture.onMove((Entity) (Object) this, pos, yRot, xRot);
        } catch (Throwable ignored) {
        }
    }
}
