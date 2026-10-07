package com.engineerclient.mixin.odin;

import com.engineerclient.EngineerClient;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.odtheking.odin.features.Module;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Odin lists a ClickGUI panel's modules by how wide their names are, longest first. Our panel is
 * listed in the order of {@link EngineerClient#getMODULES()} instead; every other panel is left
 * as Odin sorts it. The panel is the composable {@code Panel(Category, ...)} in {@code PanelKt}, which
 * sorts once, when the panel is first composed.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.clickgui.ui.PanelKt", remap = false)
public class PanelOrderMixin {

    @ModifyExpressionValue(
        method = "Panel(Lcom/odtheking/odin/features/Category;Lkotlin/jvm/functions/Function0;Landroidx/compose/runtime/Composer;I)V",
        at = @At(value = "INVOKE", target = "Lkotlin/collections/CollectionsKt;sortedWith(Ljava/lang/Iterable;Ljava/util/Comparator;)Ljava/util/List;"),
        remap = false
    )
    private static List<Module> ec$ourOrder(List<Module> sorted) {
        try {
            List<Module> order = EngineerClient.INSTANCE.getMODULES();
            if (sorted.isEmpty() || !order.contains(sorted.get(0))) return sorted;
            List<Module> ours = new ArrayList<>(sorted);
            // Anything not in the list (there should be nothing) keeps Odin's place, after ours.
            ours.sort(Comparator.comparingInt(m -> order.contains(m) ? order.indexOf(m) : order.size()));
            return ours;
        } catch (Throwable t) {
            return sorted;
        }
    }
}
