package com.adm.mixin.mixins;

import com.adm.events.core.EventBus;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.adm.events.BlockUpdateEvent;
import com.adm.features.impl.dungeon.Mimic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @WrapOperation(method = "setBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState onBlockChange(LevelChunkSection section, int sectionX, int sectionY, int sectionZ, BlockState state, Operation<BlockState> original, @Local(argsOnly = true, name = "pos") BlockPos pos) {
        BlockState old = original.call(section, sectionX, sectionY, sectionZ, state);
        if (old != state && EventBus.hasListeners(BlockUpdateEvent.class)) new BlockUpdateEvent(pos, old, state).postAndCatch();
        return old;
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"))
    private void onSetBlockEntity(BlockEntity blockEntity, CallbackInfo ci) {
        if (blockEntity instanceof TrappedChestBlockEntity && ((LevelChunk) (Object) this).getLevel().isClientSide())
            Mimic.trappedChestLoaded(blockEntity.getBlockPos());
    }
}