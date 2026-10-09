package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link RandomStuff}'s "Blur In GUI": the window in which the frame's GUI is built, so
 * {@code GuiRenderStateBlurMixin} knows which resets of the GUI state to put the blur line after.
 *
 * <p>26.x builds the GUI as a list of strata and {@code blurBeforeThisStratum} splits that list
 * in two: everything up to the marked stratum is drawn, the blur post chain runs over the main
 * target, then everything from the marked stratum on is drawn on top. So the marker is not "blur
 * this" — it is the line the blur happens at. Marked at stratum 0, nothing GUI-side is behind the
 * line: the blur lands on the world alone, and the HUD, the screen, its items and its tooltips all
 * draw afterwards, sharp.
 *
 * <p>{@code GameRenderer.extractGui} opens with {@code GuiRenderState.reset()}, so the window covers
 * that reset (stratum 0 of the frame) and also any reset some mod does part-way through: a HUD
 * cache like gnetum flushes the GUI built so far into its own framebuffer mid-frame
 * ({@code GuiRenderer.render()} ends in a reset), which spends a marker placed before it on that
 * framebuffer and wipes it from the frame. Re-marking the fresh state puts the line back in front
 * of everything that is still to come — the cached HUD's blit and the screen. The reset at the end
 * of the frame, after the GUI is drawn, is outside the window and is never marked.
 */
@Mixin(GameRenderer.class)
public class GuiBlurMixin {
    @Inject(method = "extractGui(Lnet/minecraft/client/DeltaTracker;ZZ)V", at = @At("HEAD"))
    private void ec$openBlurWindow(DeltaTracker deltaTracker, boolean renderLevel, boolean renderGui, CallbackInfo ci) {
        RandomStuff.INSTANCE.setBuildingGui(true);
    }

    @Inject(method = "extractGui(Lnet/minecraft/client/DeltaTracker;ZZ)V", at = @At("RETURN"))
    private void ec$closeBlurWindow(DeltaTracker deltaTracker, boolean renderLevel, boolean renderGui, CallbackInfo ci) {
        RandomStuff.INSTANCE.setBuildingGui(false);
    }
}
