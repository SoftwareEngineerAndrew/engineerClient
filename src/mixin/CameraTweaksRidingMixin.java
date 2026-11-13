package com.engineerclient.mixin;

import com.engineerclient.render.CameraTweaks;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class CameraTweaksRidingMixin {

    @Inject(method = "onPassengerTurned", at = @At("HEAD"))
    private void ec$ridingInputFix(Entity passenger, CallbackInfo ci) {
        CameraTweaks.INSTANCE.onPassengerTurned(passenger, ((Entity) (Object) this).getYRot());
    }
}
