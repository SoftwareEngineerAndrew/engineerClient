package com.engineerclient.mixin;

import com.engineerclient.waypoints.BrWaypoints2;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets {@link BrWaypoints2}'s wand be used by dropping it: with Edit Mode on, pressing drop while
 * holding the wand places a box instead, and the drop is cancelled here before anything reaches
 * the server — the item never leaves your hand.
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerDropMixin {

    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void ec$wandDrop(boolean fullStack, CallbackInfoReturnable<Boolean> cir) {
        if (BrWaypoints2.onDrop()) cir.setReturnValue(false);
    }
}
