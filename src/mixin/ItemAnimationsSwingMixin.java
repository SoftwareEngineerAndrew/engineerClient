package com.engineerclient.mixin;

import com.engineerclient.render.ItemAnimations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class ItemAnimationsSwingMixin {
    @Inject(method = "baseTick", at = @At("HEAD"))
    private void ec$swingTick(CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer) ItemAnimations.swingTick();
    }

    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z", at = @At("HEAD"))
    private void ec$swingStart(InteractionHand hand, SwingAnimation animation, boolean broadcast, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof LocalPlayer) ItemAnimations.swingStarted();
    }
}
