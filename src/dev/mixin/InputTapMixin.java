package com.devgineerclient.mixin;

import com.devgineerclient.recorder.InputCapture;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: every key event and (with Typed Chat on) every typed character, as the game
 * gets them from GLFW (re-posted to the game thread), before anything can cancel or consume them.
 *
 * Priority 1 so these HEAD callbacks run ahead of other mixins' HEAD injections. Observe-only and
 * optional (require = 0): if a target ever moves, the recorder only loses these lines.
 */
@Mixin(value = KeyboardHandler.class, priority = 1)
public class InputTapMixin {

    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        try {
            InputCapture.key(action, event);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(method = "charTyped(JLnet/minecraft/client/input/CharacterEvent;)V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recChar(long window, CharacterEvent event, CallbackInfo ci) {
        try {
            InputCapture.charTyped(event);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }
}
