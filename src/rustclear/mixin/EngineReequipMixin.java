package net.rustclear.mod.mixin;

import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.rustclear.mod.engine.Engine;
import net.rustclear.mod.engine.EngineItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A held dungeon item whose lore changes (the Dungeonbreaker's charge count each refill, say) stays in hand
 * instead of dipping out and back as a newly equipped item would, like vanilla does for a durability change.
 * Swapping to a different item still plays the swap. Engine world only.
 */
@Mixin(FirstPersonHandsAndItems.class)
public class EngineReequipMixin {
    @Inject(method = "shouldInstantlyReplaceVisibleItem", at = @At("HEAD"), cancellable = true)
    private void rustclear$sameEngineItem(ItemStack shown, ItemStack held, LocalPlayer player,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (Engine.isEngineClient() && ItemStack.isSameItem(shown, held)) {
            EngineItem item = EngineItem.of(held);
            if (item != null && item == EngineItem.of(shown)) {
                cir.setReturnValue(true);
            }
        }
    }
}
