package com.engineerclient.mixin;

import com.engineerclient.dungeon.Dungeonbreaker;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public class DungeonbreakerSpeedMixin {
    @ModifyReturnValue(method = "getDestroySpeed", at = @At("RETURN"))
    private float ec$zeroPing(float speed, BlockState state) {
        return (Object) this instanceof LocalPlayer && Dungeonbreaker.zeroPing() ? 1500f : speed;
    }
}
