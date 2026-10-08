package com.devgineerclient.mixin;

import com.devgineerclient.recorder.HudCapture;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every toast queued for the screen, for the Dungeon Recorder (HudCapture). Read only; require = 0. */
@Mixin(ToastManager.class)
public class ToastTapMixin {

    @Inject(method = "addToast(Lnet/minecraft/client/gui/components/toasts/Toast;)V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recToast(Toast toast, CallbackInfo ci) {
        try { HudCapture.INSTANCE.toast(toast); } catch (Throwable t) { /* never break toasts */ }
    }
}
