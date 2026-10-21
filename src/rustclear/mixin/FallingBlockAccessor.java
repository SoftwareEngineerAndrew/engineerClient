package net.rustclear.mod.mixin;

import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A falling block's block is private; debris (doors, pillars, statues) sets it without placing anything. */
@Mixin(FallingBlockEntity.class)
public interface FallingBlockAccessor {
    @Accessor("blockState")
    void rustclear$setBlockState(BlockState state);
}
