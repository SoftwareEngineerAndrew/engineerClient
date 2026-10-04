package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimItems;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** P3 Sim: the drop key is the class ability (Archer: sneak + drop), and never drops an item. Only in the sim world. */
@Mixin(LocalPlayer.class)
public class ArcherDropSimMixin {
    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void ec$classAbility(boolean fullStack, CallbackInfoReturnable<Boolean> cir) {
        if (SimItems.clientDrop()) cir.setReturnValue(false);
    }
}
