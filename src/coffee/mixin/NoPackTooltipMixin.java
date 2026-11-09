package com.coffeeclient.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** No Pack (NoPack): a tooltip asking for a Hypixel pack style (missing here) gets the vanilla one. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class NoPackTooltipMixin {
    @ModifyVariable(method = "tooltip", at = @At("HEAD"), argsOnly = true)
    private Identifier cc$noPackTooltip(Identifier style) {
        return style != null && "hypixel_skyblock".equals(style.getNamespace()) ? null : style;
    }
}
