package com.engineerclient.mixin.odin;

import com.engineerclient.misc.OdinHypeExplosion;
import com.odtheking.odin.events.ParticleEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.render.RenderOptimizer$3", remap = false)
public class HypeExplosionMixin {

    @Inject(method = "invoke(Lcom/odtheking/odin/events/ParticleEvent;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$hypeOnly(ParticleEvent event, CallbackInfo ci) {
        if (OdinHypeExplosion.keep(event)) ci.cancel();
    }
}
