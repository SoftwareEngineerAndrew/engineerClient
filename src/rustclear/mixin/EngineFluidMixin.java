package net.rustclear.mod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The dungeon's water and lava stay as recorded (the Rustclear server never flows them). Engine world only. */
@Mixin(FlowingFluid.class)
public class EngineFluidMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void rustclear$still(ServerLevel level, BlockPos pos, BlockState state, FluidState fluid, CallbackInfo ci) {
        if (EngineServer.isEngineLevel(level)) {
            ci.cancel();
        }
    }
}
