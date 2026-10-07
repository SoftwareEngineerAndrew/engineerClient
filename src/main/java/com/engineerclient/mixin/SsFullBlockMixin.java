package com.engineerclient.mixin;

import com.engineerclient.practice.SimonSaysPractice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link SimonSaysPractice}'s Full Block toggle: the practice grid's buttons get a full-face hitbox. */
@Mixin(ButtonBlock.class)
public class SsFullBlockMixin {

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void ec$fullBlock(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx, CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShape shape = SimonSaysPractice.fullBlockShape(state, pos);
        if (shape != null) cir.setReturnValue(shape);
    }
}
