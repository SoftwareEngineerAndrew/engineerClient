package com.engineerclient.mixin;

import com.engineerclient.ads.NoKnockback;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No explosion push and no bumping by other bodies for players in ADS's worlds (NoKnockback). */
@Mixin(Entity.class)
public abstract class NoPlayerKnockbackMixin {
    @Inject(method = "pushFromExplosion", at = @At("HEAD"), cancellable = true)
    private void ads$noExplosionPush(Vec3 push, CallbackInfo ci) {
        if (NoKnockback.applies((Entity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void ads$noBodyPush(Entity other, CallbackInfo ci) {
        if (NoKnockback.applies((Entity) (Object) this)) {
            ci.cancel();
        }
    }
}
