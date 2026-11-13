package com.engineerclient.mixin.odin;

import com.engineerclient.misc.OdinDoorOutline;
import com.odtheking.odin.utils.render.BoxStyle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.DoorHighlight$3", remap = false)
public class DoorHighlightOutlineMixin {

    @ModifyArg(method = "invoke(Lcom/odtheking/odin/events/RenderExtractEvent;)V", at = @At(value = "INVOKE", target = "Lcom/odtheking/odin/utils/render/RenderUtilsKt;drawStyledBox(Lcom/odtheking/odin/events/RenderExtractEvent;Lnet/minecraft/world/phys/AABB;Lcom/odtheking/odin/utils/Color;Lcom/odtheking/odin/utils/render/BoxStyle;Z)V", remap = false), index = 3, remap = false)
    private BoxStyle ec$outlineOnly(BoxStyle style) {
        return OdinDoorOutline.style(style);
    }
}
