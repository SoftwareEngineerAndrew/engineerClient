package com.coffeeclient.mixin;

import com.coffeeclient.misc.ScoreboardMove;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The movable scoreboard's hooks: vanilla's sidebar call is skipped and its objective kept for the HUD (ScoreboardMove). */
@Mixin(Hud.class)
public class ScoreboardSidebarMixin {

    /** A new frame: no sidebar until vanilla asks for one. */
    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"))
    private void cc$sidebarFrame(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        ScoreboardMove.frameStart();
    }

    /** The movable scoreboard on: vanilla's call is skipped (its objective kept); the HUD draws it. */
    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void cc$moveSidebar(GuiGraphicsExtractor graphics, Objective objective, CallbackInfo ci) {
        if (!ScoreboardMove.active || ScoreboardMove.drawing) return;
        ScoreboardMove.objective = objective;
        ci.cancel();
    }

    /**
     * The sidebar's two background fills (title and lines), noted while the HUD draws it: their
     * union is the box as actually drawn, after anything another mod hid. Wrapped, not redirected,
     * so it stacks with other mods' hooks on this method.
     */
    @WrapOperation(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
    private void cc$measureSidebar(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color, Operation<Void> original) {
        if (ScoreboardMove.drawing) ScoreboardMove.fillSeen(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
        original.call(graphics, x1, y1, x2, y2, color);
    }
}
