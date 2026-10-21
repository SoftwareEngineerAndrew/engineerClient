package net.rustclear.mod.mixin;

import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import net.rustclear.mod.engine.EnginePearls;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ender Pearls land the SkyBlock way in the engine world (EnginePearls). */
@Mixin(ThrownEnderpearl.class)
public class EnginePearlMixin {
    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void rustclear$land(HitResult hit, CallbackInfo ci) {
        if (EnginePearls.land((ThrownEnderpearl) (Object) this, hit)) {
            ci.cancel();
        }
    }
}
