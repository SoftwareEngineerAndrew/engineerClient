package com.engineerclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.odtheking.odin.features.impl.dungeon.LeapMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** While a leap is shown before it lands, the hotbar stays on the slot it was. */
@Mixin(Minecraft.class)
public abstract class LeapLockMixin {
    @WrapOperation(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;setSelectedSlot(I)V"))
    private void ec$leapHotbar(Inventory inventory, int slot, Operation<Void> original) {
        if (!LeapMenu.locked()) original.call(inventory, slot);
    }
}
