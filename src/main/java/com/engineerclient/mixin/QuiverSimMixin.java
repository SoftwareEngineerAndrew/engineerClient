package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P3 Sim: a drawn bow (Last Breath) finds arrows to draw with, as Hypixel's quiver gives them, with none in your
 * inventory. Client (the draw animation) and the sim's server alike; everywhere else this does nothing.
 */
@Mixin(Player.class)
public class QuiverSimMixin {
    @Inject(method = "getProjectile", at = @At("HEAD"), cancellable = true)
    private void ec$quiver(ItemStack heldWeapon, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack arrow = SimItems.quiverArrow((Player) (Object) this, heldWeapon);
        if (arrow != null) cir.setReturnValue(arrow);
    }
}
