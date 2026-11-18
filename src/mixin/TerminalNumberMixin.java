package com.engineerclient.mixin;

import com.adm.features.impl.skyblock.PetDisplay;
import com.engineerclient.terminals.Terminals;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The other half of Terminals' Hide Numbers' Numbers: the stack count vanilla draws in a slot's corner.
 *
 * In the numbers terminal the 1-10 are the panes' stack sizes, so any slot left to vanilla shows its
 * number whatever ADM does. ADM's own render type takes every terminal slot off vanilla, but the
 * Normal one only takes the slots still in the solution - the ones already clicked come through
 * here, numbers and all. Dropping the decorations for those slots leaves the pane itself alone:
 * terminal panes carry no durability bar and no cooldown, so the count is all that goes.
 *
 * The slot is remembered on the way into the method rather than read off the redirect, because the
 * call being redirected is handed the position and the stack but not the slot they came from. The
 * GUI is drawn on one thread and the field is written immediately before every redirected call, so
 * it is never read stale.
 */
@Mixin(AbstractContainerScreen.class)
public class TerminalNumberMixin {

    @Unique
    private static Slot ec$slot;

    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void ec$rememberSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        ec$slot = slot;
    }

    @Redirect(
        method = "extractSlot",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"
        )
    )
    private void ec$hideTerminalCount(GuiGraphicsExtractor graphics, Font font, ItemStack itemStack, int x, int y, String countText) {
        try {
            Slot slot = ec$slot;
            if (slot != null && Terminals.hidesTerminalNumber(slot)) return;
        } catch (Throwable t) {
            // never cost someone their inventory counts over this
        }
        String text = countText;
        try {
            text = PetDisplay.countText(itemStack, countText);
        } catch (Throwable ignored) {
        }
        graphics.itemDecorations(font, itemStack, x, y, text);
    }
}
