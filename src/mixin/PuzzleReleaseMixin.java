package com.engineerclient.mixin;

import com.odtheking.odin.features.impl.dungeon.puzzlesolvers.BlazeSolver;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class PuzzleReleaseMixin {

    @Inject(method = "releaseUsingItem", at = @At("HEAD"), cancellable = true)
    private void ec$puzzleRelease(Player player, CallbackInfo ci) {
        if (BlazeSolver.blocksRelease()) ci.cancel();
    }
}
