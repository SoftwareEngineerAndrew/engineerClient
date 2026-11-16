package com.engineerclient.mixin.core;

import com.engineerclient.misc.OdinDoorOutline;
import com.adm.utils.render.BoxStyle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "com.adm.features.impl.dungeon.DoorHighlight$3", remap = false)
public class DoorHighlightOutlineMixin {

    @ModifyArg(method = "invoke(Lcom/adm/events/RenderExtractEvent;)V", at = @At(value = "INVOKE", target = "Lcom/adm/utils/render/RenderUtilsKt;drawStyledBox(Lcom/adm/events/RenderExtractEvent;Lnet/minecraft/world/phys/AABB;Lcom/adm/utils/Color;Lcom/adm/utils/render/BoxStyle;Z)V", remap = false), index = 3, remap = false)
    private BoxStyle ec$outlineOnly(BoxStyle style) {
        return OdinDoorOutline.style(style);
    }
}
