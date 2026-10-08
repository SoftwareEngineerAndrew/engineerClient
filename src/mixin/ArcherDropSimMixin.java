package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimItems;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** P3 Sim: the drop key is the class ability (Archer: Ctrl+Q is Explosive Shot), and never drops an item. Only in the sim world. */
// 26.3 moved the drop key's LocalPlayer.drop into MultiPlayerGameMode.dropItem.
@Mixin(MultiPlayerGameMode.class)
public class ArcherDropSimMixin {
    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void ec$classAbility(LocalPlayer player, boolean fullStack, CallbackInfo ci) {
        if (SimItems.clientDrop(fullStack)) ci.cancel();
    }
}
