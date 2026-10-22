package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leaving the ADS world says "Leaving ADS", not "Saving world": nothing of it is saved (AdsEngineNoSaveMixin). */
@Mixin(Minecraft.class)
public abstract class AdsEngineLeaveScreenMixin {
    @Shadow @Nullable private IntegratedServer singleplayerServer;

    @Shadow public abstract void disconnect(net.minecraft.client.gui.screens.Screen screen, boolean keepResourcePacks);

    @Inject(method = "disconnectWithSavingScreen()V", at = @At("HEAD"), cancellable = true)
    private void ec$adsLeaving(CallbackInfo ci) {
        if (singleplayerServer == null || singleplayerServer != SimServer.INSTANCE.getServer()) return;
        disconnect(new GenericMessageScreen(Component.literal("Leaving ADS")), false);
        ci.cancel();
    }
}
