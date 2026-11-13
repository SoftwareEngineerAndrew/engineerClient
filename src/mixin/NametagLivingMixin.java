package com.engineerclient.mixin;

import com.engineerclient.skyblock.NametagTweaks;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public class NametagLivingMixin {
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true)
    private void ec$hideDinnerbone(LivingEntity entity, double distanceSq, CallbackInfoReturnable<Boolean> cir) {
        if (NametagTweaks.INSTANCE.hidesDinnerbone(entity)) cir.setReturnValue(false);
    }
}
