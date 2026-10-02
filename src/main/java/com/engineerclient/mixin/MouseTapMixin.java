package com.engineerclient.mixin;

import com.engineerclient.recorder.InputCapture;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: mouse buttons, scrolls and cursor moves, as GLFW reports them (re-posted to the
 * game thread). Priority 1 so the scroll is seen before {@link WandScrollMixin} or anything else
 * cancels it. Observe-only and optional (require = 0).
 */
@Mixin(value = MouseHandler.class, priority = 1)
public class MouseTapMixin {

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), require = 0, expect = 0)
    private void ec$recButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        try {
            MouseHandler self = (MouseHandler) (Object) this;
            InputCapture.button(info, action, self.xpos(), self.ypos());
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), require = 0, expect = 0)
    private void ec$recScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        try {
            InputCapture.scroll(xOffset, yOffset);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(method = "onMove(JDD)V", at = @At("HEAD"), require = 0, expect = 0)
    private void ec$recMove(long window, double x, double y, CallbackInfo ci) {
        try {
            InputCapture.move(x, y, ((MouseHandler) (Object) this).isMouseGrabbed());
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }
}
