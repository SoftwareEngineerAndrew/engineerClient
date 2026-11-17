package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Hud.class)
public class HudOverlayMixin {
    @WrapWithCondition(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;extractEffects(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private boolean ec$effects(Hud self, GuiGraphicsExtractor graphics, DeltaTracker delta) {
        return Visibility.drawsEffectHud();
    }

    @WrapWithCondition(method = "extractCameraOverlays", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;extractVignette(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/Entity;)V"))
    private boolean ec$vignette(Hud self, GuiGraphicsExtractor graphics, Entity camera) {
        return Visibility.drawsVignette();
    }

    // Vanilla asks for the 0.3 (lines) background first, then the 0.4 (title) one.
    @ModifyExpressionValue(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getBackgroundColor(F)I", ordinal = 0))
    private int ec$sidebarBody(int color) {
        return Visibility.sidebarBack(color, false);
    }

    @ModifyExpressionValue(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getBackgroundColor(F)I", ordinal = 1))
    private int ec$sidebarTitle(int color) {
        return Visibility.sidebarBack(color, true);
    }
}
