package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class ScreenTweaksNauseaMixin {

    @Inject(method = "getEffectBlendFactor", at = @At("HEAD"), cancellable = true)
    private void ec$noNausea(Holder<MobEffect> effect, float partialTick, CallbackInfoReturnable<Float> cir) {
        if (effect == MobEffects.NAUSEA && (Object) this == Minecraft.getInstance().player && Visibility.hidesNausea()) cir.setReturnValue(0f);
    }
}
