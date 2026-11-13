package com.engineerclient.mixin;

import com.engineerclient.render.ScreenTweaks;
import net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.MobEffectFogEnvironment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEffectFogEnvironment.class)
public class ScreenTweaksBlindnessMixin {

    @Inject(method = "isApplicable", at = @At("HEAD"), cancellable = true)
    private void ec$noBlindness(FogType fogType, Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof BlindnessFogEnvironment && ScreenTweaks.INSTANCE.noBlindness()) cir.setReturnValue(false);
    }
}
