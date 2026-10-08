package com.engineerclient.mixin;

import com.engineerclient.chat.ChatHider;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The chat hider's one hook: the private funnel every visible chat line passes through on its
 * way into the chat GUI (player, server-system and client-system messages alike). Cancelling
 * here drops the line from the screen only — every mod that parses chat has already seen it
 * upstream, and the game log still records it.
 */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    @Inject(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ec$hide(Component contents, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
        try {
            if (ChatHider.INSTANCE.shouldHide(contents)) ci.cancel();
        } catch (Throwable t) {
            // a broken rule must never eat the chat
        }
    }
}
