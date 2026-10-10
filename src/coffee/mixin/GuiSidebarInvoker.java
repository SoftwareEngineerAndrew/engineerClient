package com.coffeeclient.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's sidebar draw, called by the movable scoreboard's HUD (ScoreboardMove) where it was placed. */
@Mixin(Hud.class)
public interface GuiSidebarInvoker {
    @Invoker("displayScoreboardSidebar")
    void cc$displayScoreboardSidebar(GuiGraphicsExtractor graphics, Objective objective);
}
