package com.engineerclient.mixin;

import com.engineerclient.dungeon.SuperboomGhostBlock;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class SuperboomGhostMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void ec$superboomGhost(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        SuperboomGhostBlock.onUseItemOn(player, hand, hit);
    }
}
