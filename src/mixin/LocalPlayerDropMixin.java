package com.engineerclient.mixin;

import com.engineerclient.waypoints.BrWaypoints2;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link BrWaypoints2}'s wand be used by dropping it: with Edit Mode on, pressing drop while
 * holding the wand places a box instead, and the drop is cancelled here before anything reaches
 * the server — the item never leaves your hand.
 */
// 26.3 moved the drop key's LocalPlayer.drop into MultiPlayerGameMode.dropItem.
@Mixin(MultiPlayerGameMode.class)
public class LocalPlayerDropMixin {

    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void ec$wandDrop(LocalPlayer player, boolean fullStack, CallbackInfo ci) {
        if (BrWaypoints2.onDrop()) ci.cancel();
    }
}
