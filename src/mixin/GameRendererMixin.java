package com.engineerclient.mixin;

import com.engineerclient.pov.PovCapture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the teammate POV previews around the world render.
 *
 * <p><b>HEAD</b> is the "Skip Own View" path: with four previews tiling the screen there is no
 * point drawing your own view underneath them, so the preview passes run here and the main pass is
 * cancelled. The previews re-enter {@code renderLevel} themselves; {@link PovCapture}'s recursion
 * guard lets those nested calls straight through, and the guard is also why a cancelled frame
 * cannot cancel its own previews.
 *
 * <p><b>RETURN</b> is the normal path: the main world image exists and the GUI has not been drawn
 * yet, so everything after it in {@code GameRenderer.render} — the entity outline, the post chain
 * and {@code guiRenderer.render} with the preview blits, Odin's leap boxes and EC's ring — lands
 * on top in the right order.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), cancellable = true)
    private void ec$beforeLevelRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (PovCapture.INSTANCE.beforeLevelRender(deltaTracker)) ci.cancel();
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"))
    private void ec$afterLevelRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        PovCapture.INSTANCE.afterLevelRender(deltaTracker);
    }
}
