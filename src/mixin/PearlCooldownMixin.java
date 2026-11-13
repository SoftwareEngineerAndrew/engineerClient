package com.engineerclient.mixin;

import com.engineerclient.skyblock.ItemTweaks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemCooldowns.class)
public class PearlCooldownMixin {
    @Inject(method = "getCooldownPercent", at = @At("HEAD"), cancellable = true)
    private void ec$noPearlCooldown(ItemStack stack, float partialTick, CallbackInfoReturnable<Float> cir) {
        if (!stack.is(Items.ENDER_PEARL) || !ItemTweaks.INSTANCE.noPearlCooldown()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.getCooldowns() == (Object) this) cir.setReturnValue(0f);
    }
}
