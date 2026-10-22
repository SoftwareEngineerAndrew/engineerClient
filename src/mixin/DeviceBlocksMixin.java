package com.engineerclient.mixin;

import com.engineerclient.devices.DeviceSolvers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The device solvers' block changes ({@link DeviceSolvers}): each block about to change in the client's world, the
 * old state still in it. The integrated server's chunks (the P3 Sim's, a Rustclear host's) are left out.
 */
@Mixin(LevelChunk.class)
public abstract class DeviceBlocksMixin {
    @Shadow @Final Level level;

    @Shadow public abstract BlockState getBlockState(BlockPos pos);

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void ec$deviceBlock(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (!level.isClientSide()) return;
        BlockState old = getBlockState(pos);
        if (old != state) DeviceSolvers.onBlockChange(pos, old, state);
    }
}
