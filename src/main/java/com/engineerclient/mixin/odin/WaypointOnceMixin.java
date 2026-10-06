package com.engineerclient.mixin.odin;

import com.engineerclient.waypoints.PosMsgEditor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Odin 0.3.6's waypoints (which replaced /posmsg) run their command every time you walk in;
 * /posmsg sent each once per world, and the rotation's arrival texts expect that. See
 * {@link PosMsgEditor#allowFire}.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.render.waypoints.Waypoint", remap = false)
public class WaypointOnceMixin {
    @Inject(method = "fire()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$oncePerWorld(CallbackInfo ci) {
        if (!PosMsgEditor.allowFire(this)) ci.cancel();
    }
}
