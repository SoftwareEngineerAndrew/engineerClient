package com.engineerclient.mixin;

import com.engineerclient.misc.Zpew;
import com.odtheking.odin.features.impl.render.Etherwarp;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Legit Zpew: Odin's etherwarp overlay is worked out from where the camera is (the end of the teleports still on their
 * way), not from the player the server hasn't moved yet, so it shows where your next etherwarp will really land.
 * getEtherPos adds the eye height to this position itself.
 */
@Mixin(value = Etherwarp.class, remap = false)
public abstract class ZpewEtherwarpOverlayMixin {

    @ModifyVariable(method = "getEtherPos", at = @At("HEAD"), argsOnly = true)
    private Vec3 ec$zpewEtherFrom(Vec3 position) {
        try {
            Vec3 camera = Zpew.cameraPos();
            return camera != null ? camera : position;
        } catch (Throwable ignored) {
            return position;
        }
    }
}
