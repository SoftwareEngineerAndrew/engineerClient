package com.engineerclient.mixin;

import com.engineerclient.dungeon.SecretCounter;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Hud.class)
public class SecretCounterMixin {
    // Both the action bar packet and chat sent to the action bar end here; Fabric's message events only see the second.
    @ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), argsOnly = true)
    private Component ec$secretCounter(Component message) {
        return SecretCounter.overlay(message);
    }
}
