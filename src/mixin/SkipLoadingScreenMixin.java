package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Random Stuff's Skip Loading Screen: the "Loading terrain" screen opened on joining or changing
 * world is closed as soon as it opens, so the world shows while it loads in. The screen only
 * displays progress: the level load itself is tracked and reported to the server by
 * ClientPacketListener.tick, with or without it.
 */
@Mixin(ClientPacketListener.class)
public abstract class SkipLoadingScreenMixin {
    @Inject(method = "startWaitingForNewLevel", at = @At("TAIL"))
    private void ec$skipLoadingScreen(LocalPlayer player, ClientLevel level, LevelLoadingScreen.Reason reason, CallbackInfo ci) {
        if (!RandomStuff.INSTANCE.skipsLoadingScreen()) return;
        if (Minecraft.getInstance().gui.screen() instanceof LevelLoadingScreen screen) screen.onClose();
    }
}
