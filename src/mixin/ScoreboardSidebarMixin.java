package com.engineerclient.mixin;

import com.engineerclient.misc.ScoreboardLines;
import com.engineerclient.render.ScoreboardNametags;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * The scoreboard line hider's one hook: the sidebar renderer asking the scoreboard which score
 * entries it has to draw. Handing back a shorter list is the whole mechanism — the renderer then
 * measures and boxes only the lines that are left, so a hidden line leaves no gap and the sidebar
 * shrinks around it.
 *
 * Nothing else sees this. The scoreboard itself is untouched, so ADM's area detection and
 * anything else reading the sidebar still get every line; they just are not drawn.
 */
@Mixin(Hud.class)
public class ScoreboardSidebarMixin {

    @Redirect(
        method = "displayScoreboardSidebar",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/scores/Scoreboard;listPlayerScores(Lnet/minecraft/world/scores/Objective;)Ljava/util/Collection;"
        )
    )
    private Collection<PlayerScoreEntry> ec$hideSidebarLines(Scoreboard scoreboard, Objective objective) {
        try {
            return ScoreboardLines.INSTANCE.visibleEntries(scoreboard, objective);
        } catch (Throwable t) {
            // a broken pattern must never cost you the sidebar mid-run
            return scoreboard.listPlayerScores(objective);
        }
    }

    /**
     * The sidebar's title ("SKYBLOCK") is drawn from the objective rather than from a score entry,
     * so filtering the lines cannot reach it. Handing back an empty component removes the text
     * without touching the objective itself.
     */
    @Redirect(
        method = "displayScoreboardSidebar",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/scores/Objective;getDisplayName()Lnet/minecraft/network/chat/Component;"
        )
    )
    private Component ec$hideSidebarTitle(Objective objective) {
        try {
            return ScoreboardLines.INSTANCE.hidesTitle() ? Component.empty() : objective.getDisplayName();
        } catch (Throwable t) {
            return objective.getDisplayName();
        }
    }

    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"))
    private void ec$sidebarFrame(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        ScoreboardNametags.frameStart();
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void ec$moveSidebar(GuiGraphicsExtractor graphics, Objective objective, CallbackInfo ci) {
        if (ScoreboardLines.hideAll) { ci.cancel(); return; }
        if (!ScoreboardNametags.moving || ScoreboardNametags.drawing) return;
        ScoreboardNametags.objective = objective;
        ci.cancel();
    }

    @WrapOperation(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
    private void ec$sidebarFill(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color, Operation<Void> original) {
        if (ScoreboardNametags.drawing) ScoreboardNametags.fillSeen(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
        original.call(graphics, x1, y1, x2, y2, ScoreboardNametags.sidebarFill(color));
    }
}
