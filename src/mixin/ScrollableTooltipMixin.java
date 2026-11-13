package com.engineerclient.mixin;

import com.engineerclient.render.ScrollableTooltip;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.joml.Matrix3x2fStack;
import org.joml.Vector2ic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphicsExtractor.class)
public abstract class ScrollableTooltipMixin {

    @Shadow public abstract Matrix3x2fStack pose();

    @Unique private boolean ec$tooltipPushed;

    @WrapOperation(method = "tooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;positionTooltip(IIIIII)Lorg/joml/Vector2ic;"))
    private Vector2ic ec$moveTooltip(ClientTooltipPositioner positioner, int screenWidth, int screenHeight, int x, int y, int width, int height, Operation<Vector2ic> original) {
        Vector2ic pos = original.call(positioner, screenWidth, screenHeight, x, y, width, height);
        ec$tooltipPushed = ScrollableTooltip.INSTANCE.begin(pose(), pos.x(), pos.y());
        return pos;
    }

    @Inject(method = "tooltip", at = @At("RETURN"))
    private void ec$endTooltip(CallbackInfo ci) {
        if (!ec$tooltipPushed) return;
        ec$tooltipPushed = false;
        pose().popMatrix();
    }
}
