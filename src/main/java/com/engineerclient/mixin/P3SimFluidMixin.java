package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** P3 Sim: the arena's lava and water stay exactly as built (Hypixel's don't flow either). Sim server only. */
@Mixin(FlowingFluid.class)
public class P3SimFluidMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void ec$still(ServerLevel level, BlockPos pos, BlockState state, FluidState fluid, CallbackInfo ci) {
        if (level.getServer() == SimServer.INSTANCE.getServer()) ci.cancel();
    }
}
