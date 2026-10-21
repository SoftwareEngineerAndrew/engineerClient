package net.rustclear.mod.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.rustclear.mod.online.E4mcRelay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the relay code see e4mc's "domain assigned" chat line (E4mcRelay), which e4mc adds straight
 * to the chat rather than through any event. Only that one translation key is ever looked at; the
 * line still shows as usual. Optional: if neither method exists in a later version, nothing breaks.
 */
@Mixin(ChatComponent.class)
public class E4mcDomainMixin {
    @Inject(method = {"addClientSystemMessage", "addServerSystemMessage"}, at = @At("HEAD"), require = 0)
    private void rustclear$e4mcDomain(Component message, CallbackInfo ci) {
        E4mcRelay.offer(message);
    }
}
