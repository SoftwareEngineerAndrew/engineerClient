package com.engineerclient.mixin;

import com.engineerclient.skyblock.ItemProtection;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public class ItemProtectionClickMixin {
    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V", at = @At("HEAD"), cancellable = true)
    private void ec$protect(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        if (ItemProtection.blocksClick((AbstractContainerScreen<?>) (Object) this, slot, slotId, input)) ci.cancel();
    }
}
