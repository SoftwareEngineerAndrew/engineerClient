package com.engineerclient.mixin;

import com.engineerclient.skyblock.ChatTweaks;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public class ChatCopyMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void ec$copyChat(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        ChatTweaks.INSTANCE.copyChat(event);
    }
}
