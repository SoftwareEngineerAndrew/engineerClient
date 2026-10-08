package com.devgineerclient.mixin;

import com.devgineerclient.recorder.HudCapture;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat as the chat box gets it, for the Dungeon Recorder (HudCapture): every line offered to the
 * chat GUI (server, client and player messages alike, including the ones mods add themselves), the
 * ones actually shown (a mod cancelling addMessage, the chat hider among them, means offered but
 * never shown), deletions by signature and clears.
 *
 * Priority 1 so the "offered" hook runs ahead of any mod that cancels addMessage. Read only; every
 * hook is require = 0 so a changed target can never stop the game from starting.
 */
@Mixin(value = ChatComponent.class, priority = 1)
public class ChatDisplayTapMixin {

    @Inject(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"),
        require = 0, expect = 0
    )
    private void dc$recOffered(Component contents, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
        try {
            HudCapture.INSTANCE.chatOffered(contents, signature, source, tag);
        } catch (Throwable t) {
            // the recorder must never break chat
        }
    }

    @Inject(
        method = "logChatMessage(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V",
        at = @At("HEAD"),
        require = 0, expect = 0
    )
    private void dc$recShown(GuiMessage message, CallbackInfo ci) {
        try {
            HudCapture.INSTANCE.chatShown(message);
        } catch (Throwable t) {
            // the recorder must never break chat
        }
    }

    @Inject(
        method = "deleteMessage(Lnet/minecraft/network/chat/MessageSignature;)V",
        at = @At("HEAD"),
        require = 0, expect = 0
    )
    private void dc$recDelete(MessageSignature signature, CallbackInfo ci) {
        try {
            HudCapture.INSTANCE.chatDeleted(signature);
        } catch (Throwable t) {
            // the recorder must never break chat
        }
    }

    @Inject(
        method = "clearMessages(Z)V",
        at = @At("HEAD"),
        require = 0, expect = 0
    )
    private void dc$recClear(boolean clearHistory, CallbackInfo ci) {
        try {
            HudCapture.INSTANCE.chatCleared(clearHistory);
        } catch (Throwable t) {
            // the recorder must never break chat
        }
    }
}
