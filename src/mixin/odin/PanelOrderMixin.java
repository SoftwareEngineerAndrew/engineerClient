package com.engineerclient.mixin.odin;

import com.engineerclient.EngineerClient;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.odtheking.odin.clickgui.Panel;
import com.odtheking.odin.features.Module;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Odin lists a ClickGUI panel's modules by how wide their names are, longest first. Our panel is
 * listed in the order of {@link EngineerClient#getMODULES()} instead; every other panel is left
 * as Odin sorts it.
 */
@Mixin(value = Panel.class, remap = false)
public class PanelOrderMixin {

    @ModifyExpressionValue(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lkotlin/collections/CollectionsKt;sortedWith(Ljava/lang/Iterable;Ljava/util/Comparator;)Ljava/util/List;"),
        remap = false
    )
    private List<Module> ec$ourOrder(List<Module> sorted) {
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
