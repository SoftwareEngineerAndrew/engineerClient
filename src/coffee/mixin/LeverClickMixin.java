package com.coffeeclient.mixin;

import com.coffeeclient.misc.Termsim;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Termsim's Lever Clicks: a left click on a block, before the game does anything with it; a lever it takes goes no further. */
@Mixin(Minecraft.class)
public abstract class LeverClickMixin {
    @Shadow public HitResult hitResult;

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void cc$leftClick(CallbackInfoReturnable<Boolean> cir) {
        if (hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && Termsim.onLeftClickBlock(hit.getBlockPos())) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
    private void cc$holdLever(boolean attacking, CallbackInfo ci) {
        if (attacking && hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && Termsim.holdsLever(hit.getBlockPos())) ci.cancel();
    }
}
