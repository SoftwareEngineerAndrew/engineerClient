package com.engineerclient.mixin;

import com.engineerclient.misc.DoorLoad;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link DoorLoad}: the three ways the client tells the server it clicked a block - start mining,
 * mining onto a new block while holding left click, and right click on a block - are refused here,
 * before anything is sent, for a wither door whose room hasn't loaded. A refused right click returns
 * PASS, so the held item is still used as if aimed at air (an AOTV still teleports).
 */
@Mixin(MultiPlayerGameMode.class)
public class DoorLoadClickMixin {

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void ec$doorLoadStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (DoorLoad.refuseClick(pos)) cir.setReturnValue(false);
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void ec$doorLoadContinue(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (DoorLoad.refuseClick(pos)) cir.setReturnValue(false);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void ec$doorLoadUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (DoorLoad.refuseClick(hit.getBlockPos())) cir.setReturnValue(InteractionResult.PASS);
    }
}
