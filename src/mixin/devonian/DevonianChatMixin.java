package com.engineerclient.mixin.devonian;

import com.engineerclient.pf.DevonianBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Devonian's "DungeonsApi failed to fetch data for user X (Rate Limited)" lines out of chat
 * (they go to the game log instead; see {@link DevonianBridge#quietChat}). Party Finder and Hub
 * Nametag Stats hand Devonian a fetch for every player they show, so its API rate-limits often and
 * each failure would otherwise be a chat line.
 *
 * <p>Hooked at Devonian's general chat helper, matched by text, rather than at the lambda that
 * builds the line: those compiler-made names move between Devonian versions. Optional (this config
 * is not required), so without Devonian nothing here applies.
 */
@Pseudo
@Mixin(targets = "com.github.synnerz.devonian.api.ChatUtils", remap = false)
public class DevonianChatMixin {
    @Inject(method = "sendMessage(Ljava/lang/String;Z)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$quietFetchFailures(String message, boolean prefix, CallbackInfo ci) {
        if (DevonianBridge.INSTANCE.quietChat(message)) ci.cancel();
    }
}
