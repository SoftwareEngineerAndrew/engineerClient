package com.coffeeclient.mixin;

import com.coffeeclient.misc.Termsim;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Termsim's Lever Clicks: a left click on a block, before the game does anything with it (it sends nothing in adventure
 * mode). At the switch on what's aimed at, past startAttack's early outs: a click the game drops (the 10 ticks after an
 * air swing, busy hands) isn't a click.
 */
@Mixin(Minecraft.class)
public abstract class LeverClickMixin {
    @Shadow public HitResult hitResult;

    @Inject(method = "startAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/HitResult;getType()Lnet/minecraft/world/phys/HitResult$Type;"))
    private void cc$leftClick(CallbackInfoReturnable<Boolean> cir) {
        if (hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) Termsim.onLeftClickBlock(hit.getBlockPos());
    }
}
