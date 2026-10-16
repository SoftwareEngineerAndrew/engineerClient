package com.engineerclient.mixin.odin;

import com.engineerclient.misc.OdinOnScreen;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Odin's HUDs never off screen in play (OdinOnScreen): {@code render} with {@code example} false draws them in play;
 * true is the HUD editor, which clamps them itself.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.clickgui.HudLayer", remap = false)
public class HudOnScreenMixin {
    @WrapMethod(method = "render", remap = false)
    private void ec$hudsOnScreen(GuiGraphicsExtractor graphics, boolean example, int mouseX, int mouseY, Operation<Void> original) {
        if (example) {
            original.call(graphics, example, mouseX, mouseY);
            return;
        }
        OdinOnScreen.hudsOnScreen(() -> original.call(graphics, example, mouseX, mouseY));
    }
}
