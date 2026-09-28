package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Random Stuff's Hide Item Names: the name that pops up over the hotbar when you switch items, not drawn. */
@Mixin(Gui.class)
public class GuiItemNameMixin {

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void ec$hideSelectedItemName(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (RandomStuff.INSTANCE.hidesItemNames()) ci.cancel();
    }
}
