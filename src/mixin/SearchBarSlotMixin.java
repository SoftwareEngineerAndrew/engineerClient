package com.engineerclient.mixin;

import com.engineerclient.skyblock.SearchBar;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public class SearchBarSlotMixin {
    @Inject(method = "extractSlot", at = @At("TAIL"))
    private void ec$shadeMiss(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        SearchBar.INSTANCE.shadeSlot(graphics, slot);
    }
}
