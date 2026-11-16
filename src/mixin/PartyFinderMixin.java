package com.engineerclient.mixin;

import com.adm.features.impl.dungeon.BetterPartyFinder;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Better Party Finder's overlay: text drawn over each listing after its head, and its tooltip lines rewritten. */
@Mixin(AbstractContainerScreen.class)
public class PartyFinderMixin {

    @Inject(method = "extractSlot", at = @At("RETURN"))
    private void ec$partyFinderSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        try {
            BetterPartyFinder.INSTANCE.drawSlot((AbstractContainerScreen<?>) (Object) this, graphics, slot);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true)
    private void ec$partyFinderTooltip(ItemStack itemStack, CallbackInfoReturnable<List<Component>> cir) {
        List<Component> original = cir.getReturnValue();
        if (original == null) return;
        List<Component> replaced;
        try {
            replaced = BetterPartyFinder.INSTANCE.decorateTooltip((AbstractContainerScreen<?>) (Object) this, itemStack, original);
        } catch (Throwable t) {
            return; // a broken tooltip is worse than a missing line
        }
        if (replaced != original) cir.setReturnValue(replaced);
    }
}
