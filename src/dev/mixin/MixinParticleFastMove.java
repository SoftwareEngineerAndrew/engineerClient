package com.devgineerclient.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Particle.class, priority = 1500)
public abstract class MixinParticleFastMove {

    @Shadow
    public abstract AABB getBoundingBox();

    @Shadow
    public abstract void setBoundingBox(AABB aabb);

    @Shadow
    protected abstract void setLocationFromBoundingbox();

    @Inject(method = "move(DDD)V", at = @At("HEAD"), cancellable = true)
    private void engineerclient$skipParticleBlockCollisions(double dx, double dy, double dz, CallbackInfo ci) {
        if (dx != 0.0D || dy != 0.0D || dz != 0.0D) {
            this.setBoundingBox(this.getBoundingBox().move(dx, dy, dz));
            this.setLocationFromBoundingbox();
        }
        ci.cancel();
    }
}
