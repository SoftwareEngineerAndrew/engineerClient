package com.engineerclient.mixin;

import com.engineerclient.ads.NoKnockback;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No knockback from hits, arrows or mobs for players in ADS's worlds (NoKnockback): both of LivingEntity's forms. */
@Mixin(LivingEntity.class)
public abstract class NoLivingKnockbackMixin {
    @Inject(method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;F)V", at = @At("HEAD"), cancellable = true)
    private void ads$noKnockback(double strength, double x, double z, DamageSource source, float amount, CallbackInfo ci) {
        if (NoKnockback.applies((Entity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V", at = @At("HEAD"), cancellable = true)
    private void ads$noKnockbackFull(double strength, double x, double z, DamageSource source, float amount, boolean b, CallbackInfo ci) {
        if (NoKnockback.applies((Entity) (Object) this)) {
            ci.cancel();
        }
    }
}
