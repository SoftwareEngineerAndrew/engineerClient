package com.engineerclient.mixin;

import com.engineerclient.dungeon.Dungeonbreaker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public class DungeonbreakerSpeedMixin {
    @Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
    private void ec$zeroPing(BlockState state, CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof LocalPlayer && Dungeonbreaker.zeroPing()) cir.setReturnValue(1500f);
    }
}
