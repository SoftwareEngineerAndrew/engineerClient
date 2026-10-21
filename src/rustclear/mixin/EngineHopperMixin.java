package net.rustclear.mod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The dungeon's hoppers are decoration: they never move or pull in items (keys, drops). Engine world only. */
@Mixin(HopperBlockEntity.class)
public class EngineHopperMixin {
    @Inject(method = "pushItemsTick", at = @At("HEAD"), cancellable = true)
    private static void rustclear$noPush(Level level, BlockPos pos, BlockState state, HopperBlockEntity hopper, CallbackInfo ci) {
        if (EngineServer.isEngineLevel(level)) {
            ci.cancel();
        }
    }

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private static void rustclear$noPull(Level level, BlockPos pos, BlockState state, Entity entity, HopperBlockEntity hopper,
                                         CallbackInfo ci) {
        if (EngineServer.isEngineLevel(level)) {
            ci.cancel();
        }
    }
}
