package com.bloodrushwaypoints.mixin;

import com.bloodrushwaypoints.pf.PartyFinderStats;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Rewrites a container item's tooltip lines in place. The only consumer is Party Finder Stats,
 * which appends each listed member's Catacombs level / secrets / floor PB to their row; for
 * every other screen and item the original list is returned untouched.
 */
@Mixin(AbstractContainerScreen.class)
public class ContainerTooltipMixin {

    @Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true)
    private void brw$partyFinderStats(ItemStack itemStack, CallbackInfoReturnable<List<Component>> cir) {
        List<Component> original = cir.getReturnValue();
        if (original == null) return;
        List<Component> replaced;
        try {
            replaced = PartyFinderStats.INSTANCE.decorate((AbstractContainerScreen<?>) (Object) this, itemStack, original);
        } catch (Throwable t) {
            return; // a broken tooltip is worse than a missing column
        }
        if (replaced != original) cir.setReturnValue(replaced);
    }
}
