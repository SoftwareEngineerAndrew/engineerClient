package com.engineerclient.mixin;

import com.engineerclient.recorder.ThumbCapture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The end of a whole frame, for the Dungeon Recorder's opt-in frame thumbnails.
 *
 * <p>By the time {@code renderFrame} returns, the main render target holds the finished frame:
 * the world, every mod's world rendering, the HUD, the open screen and anything drawn over it
 * (that is what {@code blitToScreen} just showed). A thumbnail taken here is what you saw, which
 * a hook at the end of the world pass would not be. Optional: if the target ever moves, the
 * thumbnails simply stop and the game is unaffected.
 */
@Mixin(Minecraft.class)
public class RecThumbFrameMixin {

    @Inject(method = "renderFrame(Z)V", at = @At("RETURN"), require = 0, expect = 0)
    private void ec$recThumbFrame(boolean advanceGameTime, CallbackInfo ci) {
        // The recorder must never break the game: whatever it hits stays here.
        try {
            ThumbCapture.INSTANCE.onFrameEnd();
        } catch (Throwable ignored) {
        }
    }
}
