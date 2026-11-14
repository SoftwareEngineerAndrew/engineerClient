package com.engineerclient.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Hud.class)
public interface HudSidebarInvoker {
    @Invoker("displayScoreboardSidebar")
    void ec$displayScoreboardSidebar(GuiGraphicsExtractor graphics, Objective objective);
}
