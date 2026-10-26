package com.engineerclient.mixin.odin;

import com.engineerclient.tpmaze.TeamTpMaze;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Odin's TP Maze solver drawing its boxes and tracer: Team TP Maze ({@link TeamTpMaze}) draws the shared map instead while it
 * is on and in the maze, so the two don't paint the same pads in different colours.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.puzzlesolvers.TPMazeSolver", remap = false)
public class TpMazeRenderMixin {

    @Inject(method = "onRenderWorld", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ec$teamMaze(CallbackInfo ci) {
        if (TeamTpMaze.drawsInstead()) ci.cancel();
    }
}
