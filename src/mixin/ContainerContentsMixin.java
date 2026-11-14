package com.engineerclient.mixin;

import com.engineerclient.terminals.ContainerContentsEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(AbstractContainerMenu.class)
public class ContainerContentsMixin {
    @Inject(method = "initializeContents", at = @At("TAIL"))
    private void ec$contents(int stateId, List<ItemStack> items, ItemStack carried, CallbackInfo ci) {
        new ContainerContentsEvent((AbstractContainerMenu) (Object) this).postAndCatch();
    }
}
