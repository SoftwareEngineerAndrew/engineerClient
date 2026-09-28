package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link RandomStuff}'s "Blur In GUI": says where the frame's blur pass goes.
 *
 * <p>26.1.2 builds the GUI as a list of strata and {@code blurBeforeThisStratum} splits that list
 * in two: everything up to the marked stratum is drawn, the blur post chain runs over the whole
 * main target, then everything from the marked stratum on is drawn on top. So the marker is not
 * "blur this" — it is the line the blur happens at.
 *
 * <p>{@code Gui.extractRenderState} is the first thing to touch the GUI state after
 * {@code GuiRenderState.reset()}, so marking at its HEAD marks stratum 0 and nothing GUI-side is
 * ever behind the line: the blur lands on the world alone, and the HUD, the screen, its items and
 * its tooltips all draw afterwards, sharp. Vanilla's own menu blur instead marks at the screen's
 * background stratum, which puts the HUD behind the line and blurs it along with the world — which
 * is why this hooks here rather than reusing that call, and why {@code ScreenBlurMixin} has to drop
 * it: {@code blurBeforeThisStratum} throws "Can only blur once per frame".
 *
 * <p>Nothing here runs when the HUD is not being extracted (no level, so nothing to blur), which
 * is the other half of the condition {@link RandomStuff#blursGui()} checks.
 */
@Mixin(Gui.class)
public class GuiBlurMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void ec$blurBehindGui(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (RandomStuff.INSTANCE.blursGui()) extractor.blurBeforeThisStratum();
    }
}
