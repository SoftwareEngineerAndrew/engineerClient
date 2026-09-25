package com.engineerclient.mixin;

import com.engineerclient.misc.ScoreboardLines;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;

/**
 * The scoreboard line hider's one hook: the sidebar renderer asking the scoreboard which score
 * entries it has to draw. Handing back a shorter list is the whole mechanism — the renderer then
 * measures and boxes only the lines that are left, so a hidden line leaves no gap and the sidebar
 * shrinks around it.
 *
 * Nothing else sees this. The scoreboard itself is untouched, so Odin's area detection and
 * anything else reading the sidebar still get every line; they just are not drawn.
 */
@Mixin(Gui.class)
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
}
