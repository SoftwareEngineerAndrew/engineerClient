package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimItems;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P3 Sim: Last Breath released on the sim's server fires Hypixel's arrows (Duplex, Terror's Hydra Strike) through
 * Bows instead of vanilla's single arrow. Every other bow, and anywhere else, stays vanilla.
 */
@Mixin(BowItem.class)
public class LastBreathSimMixin {
    @Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
    private void ec$lastBreath(ItemStack itemStack, Level level, LivingEntity entity, int remainingTime, CallbackInfoReturnable<Boolean> cir) {
        Boolean handled = SimItems.releaseBow(itemStack, level, entity, remainingTime);
        if (handled != null) cir.setReturnValue(handled);
    }
}
