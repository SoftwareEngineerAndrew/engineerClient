package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import net.minecraft.client.Camera;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Camera.class)
public class ScreenTweaksBlindnessSkyMixin {

    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;hasEffect(Lnet/minecraft/core/Holder;)Z"))
    private boolean ec$skyBlindness(LivingEntity entity, Holder<MobEffect> effect) {
        if (effect == MobEffects.BLINDNESS && Visibility.hidesBlindness()) return false;
        return entity.hasEffect(effect);
    }
}
