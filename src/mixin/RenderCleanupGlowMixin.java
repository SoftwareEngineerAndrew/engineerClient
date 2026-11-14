package com.engineerclient.mixin;

import com.engineerclient.render.ScreenTweaks;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class RenderCleanupGlowMixin {
    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void ec$disableGlowing(CallbackInfoReturnable<Boolean> cir) {
        if (ScreenTweaks.INSTANCE.disablesGlowing()) cir.setReturnValue(false);
    }
}
