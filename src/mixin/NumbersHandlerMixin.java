package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import com.engineerclient.practice.InfNumbersSim;
import com.odtheking.odin.utils.Color;
import com.odtheking.odin.utils.skyblock.dungeon.terminals.terminalhandler.NumbersHandler;
import kotlin.Pair;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Odin's numbers solver, with the number text taken off it: for /termsim inf, which has no numbers
 * at all, and for Random Stuff's Hide Terminal Numbers.
 *
 * Odin hands the solver's drawing back as (colour, text) per slot and every render type - its own,
 * the vanilla one and the custom GUI - draws that same pair, so dropping the text here is enough to
 * hide the numbers everywhere Odin draws them.
 */
@Mixin(value = NumbersHandler.class, remap = false)
public class NumbersHandlerMixin {
    @Inject(method = "solve", at = @At("HEAD"), cancellable = true)
    private void ec$infiOrder(List<Slot> slots, int updatedIndex, CallbackInfoReturnable<List<Integer>> cir) {
        if (InfNumbersSim.active()) cir.setReturnValue(new ArrayList<>(InfNumbersSim.getQueue()));
    }

    @Inject(method = "renderSlot", at = @At("RETURN"), cancellable = true)
    private void ec$noNumbers(int slotIndex, CallbackInfoReturnable<Pair<Color, String>> cir) {
        if (cir.getReturnValue() == null) return;
        if (InfNumbersSim.active() || RandomStuff.hidesTerminalNumbers()) {
            cir.setReturnValue(new Pair<>(cir.getReturnValue().getFirst(), null));
        }
    }
}
