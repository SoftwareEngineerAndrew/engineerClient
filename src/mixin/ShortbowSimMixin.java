package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimItems;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P3 Sim: a left click with a shortbow shoots it (only in the sim world; the swing still happens). At the switch on
 * what's aimed at, past startAttack's early outs: a click inside the 10 ticks after an air swing (or with busy hands)
 * does nothing in the game, so Hypixel never hears of it either.
 */
@Mixin(Minecraft.class)
public class ShortbowSimMixin {
    @Inject(method = "startAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/HitResult;getType()Lnet/minecraft/world/phys/HitResult$Type;"))
    private void ec$shortbowLeftClick(CallbackInfoReturnable<Boolean> cir) {
        SimItems.clientLeftClick();
    }
}
