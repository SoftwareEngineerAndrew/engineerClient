package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Enter finish a sign edit screen instead of dropping to the next line, for
 * {@link RandomStuff}'s "Enter Confirms Sign".
 *
 * <p>Hypixel asks for text — a Bazaar or Auction House search, a rename, an amount — by opening a
 * sign, where vanilla Enter means "next line". Since only the first line is ever read, Enter may
 * as well mean "done", which makes searching type-and-Enter instead of type-and-reach-for-the-mouse.
 *
 * <p>Nothing is sent from here, and nothing reimplements the Done button. {@code onClose()} is
 * public and is literally what Done runs ({@code onClose} → {@code onDone} →
 * {@code setScreen(null)}), and the game's own {@code removed()} is what puts the typed lines on
 * the wire. So this presses Done through the screen's own door and lets vanilla do the rest.
 */
@Mixin(AbstractSignEditScreen.class)
public class SignEnterMixin {

    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z", at = @At("HEAD"), cancellable = true)
    private void ec$enterFinishesSign(KeyEvent keyEvent, CallbackInfoReturnable<Boolean> cir) {
        if (!RandomStuff.INSTANCE.signEnterFinishes(keyEvent.key())) return;
        ((AbstractSignEditScreen) (Object) this).onClose();
        cir.setReturnValue(true);
    }
}
