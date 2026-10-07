package com.engineerclient.mixin;

import com.engineerclient.waypoints.BrWaypoints2;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link BrWaypoints2}'s wand clicks: while a box face is selected, left click pushes it out and
 * right click pulls it in, and the swing, the mining and the item use they would otherwise be are
 * cancelled here so nothing reaches the server.
 */
@Mixin(Minecraft.class)
public class WandInputMixin {

    @Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
    private void ec$wandPush(CallbackInfoReturnable<Boolean> cir) {
        if (BrWaypoints2.onAttack()) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
    private void ec$wandNoMining(boolean attacking, CallbackInfo ci) {
        if (BrWaypoints2.blocksContinueAttack()) ci.cancel();
    }

    @Inject(method = "startUseItem()V", at = @At("HEAD"), cancellable = true)
    private void ec$wandPull(CallbackInfo ci) {
        if (BrWaypoints2.onUse()) ci.cancel();
    }
}
