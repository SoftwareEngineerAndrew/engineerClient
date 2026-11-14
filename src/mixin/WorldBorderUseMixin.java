package com.engineerclient.mixin;

import com.engineerclient.dungeon.ShadowAssassinJumpHider;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public class WorldBorderUseMixin {
    @WrapOperation(method = "startUseItem",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/border/WorldBorder;isWithinBounds(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean ec$ignoreBorder(WorldBorder border, BlockPos pos, Operation<Boolean> original) {
        return ShadowAssassinJumpHider.ignoresBorder() || original.call(border, pos);
    }
}
