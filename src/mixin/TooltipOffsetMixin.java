package com.engineerclient.mixin;

import com.engineerclient.render.ScrollableTooltip;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;
import org.joml.Vector2ic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiGraphicsExtractor.class)
public class TooltipOffsetMixin {
    @ModifyExpressionValue(method = "tooltip", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;pushMatrix()Lorg/joml/Matrix3x2fStack;"))
    private Matrix3x2fStack ec$offsetTooltip(Matrix3x2fStack pose, @Local Vector2ic corner) {
        ScrollableTooltip.place(pose, corner.x(), corner.y());
        return pose;
    }
}
