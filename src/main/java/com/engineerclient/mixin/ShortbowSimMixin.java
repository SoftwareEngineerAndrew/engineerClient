package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimItems;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** P3 Sim: a left click with a shortbow shoots it (only in the sim world; the swing still happens). */
@Mixin(Minecraft.class)
public class ShortbowSimMixin {
    @Inject(method = "startAttack", at = @At("HEAD"))
    private void ec$shortbowLeftClick(CallbackInfoReturnable<Boolean> cir) {
        SimItems.clientLeftClick();
    }
}
