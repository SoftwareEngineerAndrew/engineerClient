package com.devgineerclient.mixin;

import com.devgineerclient.recorder.InputCapture;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: your look turns as the mouse drives them (Entity.turn is what MouseHandler
 * calls once a frame, after sensitivity, smoothing and invert). Every entity has this method, so
 * the LocalPlayer check comes first and costs one instanceof for everything else. Observe-only and
 * optional (require = 0).
 */
@Mixin(value = Entity.class, priority = 1)
public class LookTapMixin {

    @Inject(method = "turn(DD)V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recTurn(double yRot, double xRot, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer)) return;
        try {
            InputCapture.turn(yRot, xRot);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }
}
