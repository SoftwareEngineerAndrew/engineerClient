package com.devgineerclient.mixin;

import com.devgineerclient.recorder.HudCapture;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Titles, subtitles and the action bar as the HUD is told to show them, for the Dungeon Recorder
 * (HudCapture). Hooking the setters rather than the packets also catches the ones mods set
 * themselves (Odin's and ours). Read only; require = 0 so a changed target can never stop the game
 * from starting.
 */
@Mixin(Gui.class)
public class GuiTitleTapMixin {

    @Inject(method = "setOverlayMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recOverlay(Component message, boolean animate, CallbackInfo ci) {
        try { HudCapture.INSTANCE.actionBar(message, animate); } catch (Throwable t) { /* never break the HUD */ }
    }

    @Inject(method = "setTitle(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recTitle(Component title, CallbackInfo ci) {
        try { HudCapture.INSTANCE.hudText("title", title); } catch (Throwable t) { /* never break the HUD */ }
    }

    @Inject(method = "setSubtitle(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recSubtitle(Component subtitle, CallbackInfo ci) {
        try { HudCapture.INSTANCE.hudText("subtitle", subtitle); } catch (Throwable t) { /* never break the HUD */ }
    }

    @Inject(method = "setTimes(III)V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recTimes(int fadeIn, int stay, int fadeOut, CallbackInfo ci) {
        try { HudCapture.INSTANCE.hudTimes(fadeIn, stay, fadeOut); } catch (Throwable t) { /* never break the HUD */ }
    }

    @Inject(method = "clearTitles()V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recClear(CallbackInfo ci) {
        try { HudCapture.INSTANCE.hudPlain("clear"); } catch (Throwable t) { /* never break the HUD */ }
    }

    @Inject(method = "resetTitleTimes()V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recReset(CallbackInfo ci) {
        try { HudCapture.INSTANCE.hudPlain("reset"); } catch (Throwable t) { /* never break the HUD */ }
    }
}
