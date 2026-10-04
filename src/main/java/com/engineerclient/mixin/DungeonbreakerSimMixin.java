package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimItems;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The P3 Sim plays in adventure mode, where the client gives up on a block hit before Fabric's
 * AttackBlockCallback ever fires. The Dungeonbreaker needs those hits: in the sim world, with it in
 * hand, a hit goes to the sim's server instead. Everywhere else this does nothing.
 */
@Mixin(MultiPlayerGameMode.class)
public class DungeonbreakerSimMixin {
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void ec$dungeonbreakerInSim(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        // Only the sim's own hits (Dungeonbreaker, levers, Superboom) stop here; any other hit goes on as
        // vanilla and the server refuses it and sends the block back, as Hypixel's does (Sim.guardBlocks).
        if (SimItems.clientHitBlock(pos)) cir.setReturnValue(false);
    }
}
