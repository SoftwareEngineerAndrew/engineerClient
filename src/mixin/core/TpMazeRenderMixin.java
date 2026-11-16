package com.engineerclient.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ADM's own TP Maze solver is switched off: Team TP Maze ({@link com.engineerclient.tpmaze.TeamTpMaze}) takes its place, and its
 * settings take the old ones' spot in Puzzle Solvers. Nothing of ADM's runs: no room scan, no teleport tracking, no drawing.
 */
@Pseudo
@Mixin(targets = "com.adm.features.impl.dungeon.puzzlesolvers.TPMazeSolver", remap = false)
public class TpMazeRenderMixin {

    @Inject(method = "onRoomEnter", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ec$noRoom(CallbackInfo ci) { ci.cancel(); }

    @Inject(method = "tpPacket", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ec$noPacket(CallbackInfo ci) { ci.cancel(); }

    @Inject(method = "onRenderWorld", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ec$noRender(CallbackInfo ci) { ci.cancel(); }
}
