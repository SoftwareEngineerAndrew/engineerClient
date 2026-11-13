package com.engineerclient.mixin;

import com.engineerclient.skyblock.MoveDisconnectButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class MoveDisconnectMixin extends Screen {
    @Shadow private Button disconnectButton;

    protected MoveDisconnectMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void ec$moveDisconnect(CallbackInfo ci) {
        MoveDisconnectButton.move(disconnectButton, width, height);
    }
}
