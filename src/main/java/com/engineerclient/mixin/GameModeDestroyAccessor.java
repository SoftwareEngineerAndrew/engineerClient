package com.engineerclient.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Dungeon Recorder: the block you are breaking and how far along it is (getDestroyStage is public,
 * the position and the exact progress are not), for the "mine" member of the per-tick me line.
 */
@Mixin(MultiPlayerGameMode.class)
public interface GameModeDestroyAccessor {

    @Accessor("destroyBlockPos")
    BlockPos ec$destroyBlockPos();

    @Accessor("destroyProgress")
    float ec$destroyProgress();
}
