package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Visibility's Lava As Water: lava is drawn with water's model (textures, tint and translucent layer). */
@Mixin(FluidRenderer.class)
public class LavaAsWaterMixin {
    @WrapOperation(method = "tesselate", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidStateModelSet;get(Lnet/minecraft/world/level/material/FluidState;)Lnet/minecraft/client/renderer/block/FluidModel;"))
    private FluidModel ec$lavaAsWater(FluidStateModelSet models, FluidState state, Operation<FluidModel> original) {
        if (Visibility.lavaAsWater() && state.is(net.minecraft.tags.FluidTags.LAVA))
            return original.call(models, state.isSource() ? Fluids.WATER.getSource(false) : Fluids.FLOWING_WATER.getFlowing(state.getAmount(), false));
        return original.call(models, state);
    }
}
