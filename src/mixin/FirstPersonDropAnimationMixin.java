package com.engineerclient.mixin;

import com.engineerclient.misc.DropAnimation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Drop Animation in {@link com.engineerclient.misc.RandomStuff}: after a rejected drop the
 * first-person hand keeps the dropped stack drawn fully raised, until the held item or slot changes.
 */
@Mixin(FirstPersonHandsAndItems.class)
public abstract class FirstPersonDropAnimationMixin {
    @Shadow
    private ItemStack mainHandItem;

    @Shadow
    private float mainHandHeight;

    @Inject(method = "tick", at = @At("HEAD"))
    private void ec$dropTick(CallbackInfo ci) {
        DropAnimation.tick();
    }

    @Inject(method = "tick", at = @At(value = "INVOKE_ASSIGN", target = "Lnet/minecraft/client/player/LocalPlayer;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"))
    private void ec$dropChanged(CallbackInfo ci, @Local LocalPlayer player, @Local ItemStack held) {
        if (!DropAnimation.shouldProceed() || held.getItem() == Items.AIR) return;
        ItemStack dropped = DropAnimation.getDroppedItem();
        if (dropped == null) return;
        if (dropped.getItem() != held.getItem() || DropAnimation.getSelectedSlot() != player.getInventory().getSelectedSlot()) {
            DropAnimation.clearData();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void ec$keepRaised(CallbackInfo ci) {
        if (!DropAnimation.shouldProceed()) return;
        ItemStack dropped = DropAnimation.getDroppedItem();
        if (dropped == null || dropped.getItem() == Items.AIR) return;
        mainHandItem = dropped;
        mainHandHeight = 1;
    }
}
