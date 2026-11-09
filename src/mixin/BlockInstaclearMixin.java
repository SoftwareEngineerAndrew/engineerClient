package com.engineerclient.mixin;

import com.engineerclient.misc.BlockInstaclear;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link BlockInstaclear}: a left click (the swing Hypixel opens a door on), holding it, or a right click aimed at a
 * wither door whose room isn't ready is cancelled here, before the game sends anything.
 */
@Mixin(Minecraft.class)
public class BlockInstaclearMixin {

    @Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
    private void ec$instaclearAttack(CallbackInfoReturnable<Boolean> cir) {
        if (BlockInstaclear.onAttack()) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
    private void ec$instaclearHold(boolean attacking, CallbackInfo ci) {
        if (attacking && BlockInstaclear.blocksContinueAttack()) ci.cancel();
    }

    @Inject(method = "startUseItem()V", at = @At("HEAD"), cancellable = true)
    private void ec$instaclearUse(CallbackInfo ci) {
        if (BlockInstaclear.onUse()) ci.cancel();
    }
}
