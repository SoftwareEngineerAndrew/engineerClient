package com.engineerclient.mixin;

import com.engineerclient.dungeon.Dungeonbreaker;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class DungeonbreakerDelayMixin {
    @Shadow private int destroyDelay;

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"))
    private void ec$instantBreak(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (destroyDelay > 0 && Dungeonbreaker.instantBreak()) destroyDelay = 0;
    }
}
