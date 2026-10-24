package com.engineerclient.mixin;

import com.engineerclient.pov.PovCapture;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the teammate POV previews from {@code GameRenderer.render}, beside the main world pass and never inside it.
 *
 * <p>A preview pass is a whole {@code renderLevel} of its own, so it is called beside the main one, never from inside
 * it (the old HEAD/RETURN hooks): no level render is nested in another, and the passes come after the main frame's
 * entity outline is blitted, so they can't overwrite it first. What a pass must leave out on Vulkan is in
 * {@code PovCapture.renderFeed}.
 *
 * <p><b>The main {@code renderLevel} call</b> is the "Skip Own View" path: with four previews tiling the screen there
 * is no point drawing your own view underneath them, so the preview passes run in its place and it is not called.
 *
 * <p><b>Before {@code FogRenderer.endFrame}</b> is the normal path: the main world image is finished (entity outline
 * blitted, post effects applied) and the GUI has not been drawn yet, so the preview blits, Odin's leap boxes and
 * EC's ring all land on top in {@code guiRenderer.render}.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    // 26.3: renderLevel() no longer takes the DeltaTracker; it is the game's own one.
    @WrapWithCondition(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private boolean ec$mainLevelPass(GameRenderer self) {
        return !PovCapture.INSTANCE.replaceMainPass(Minecraft.getInstance().getDeltaTracker());
    }

    @Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void ec$afterMainFrame(CallbackInfo ci) {
        PovCapture.INSTANCE.afterMainPass(Minecraft.getInstance().getDeltaTracker());
    }
}
