package com.engineerclient.mixin;

import com.engineerclient.terminals.TermTitles;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class TermTitleMixin {
    @Inject(method = "setTitle", at = @At("HEAD"), cancellable = true)
    private void ec$title(Component title, CallbackInfo ci) {
        if (TermTitles.intercept(title)) ci.cancel();
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"), cancellable = true)
    private void ec$subtitle(Component subtitle, CallbackInfo ci) {
        if (TermTitles.intercept(subtitle)) ci.cancel();
    }
}
