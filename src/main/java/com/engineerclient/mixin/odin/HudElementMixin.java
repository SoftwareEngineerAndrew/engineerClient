package com.engineerclient.mixin.odin;

import com.engineerclient.misc.RandomStuff;
import com.odtheking.odin.clickgui.settings.impl.HudElement;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Random Stuff's Hide Health/Mana Above %: Odin's own Health HUD and Mana HUD are simply not drawn
 * while the stat is above the threshold. Every Odin HUD element is drawn through this one method,
 * so it is the one place to skip a particular element without touching the module that owns it.
 * The HUD editor (example = true) always draws, so they can still be moved.
 */
@Mixin(value = HudElement.class, remap = false)
public class HudElementMixin {

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$hideAboveThreshold(GuiGraphicsExtractor context, boolean example, CallbackInfo ci) {
        if (!example && RandomStuff.INSTANCE.hidesOdinHud((HudElement) (Object) this)) ci.cancel();
    }
}
