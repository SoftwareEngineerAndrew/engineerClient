package com.coffeeclient.mixin;

import com.coffeeclient.leap.InstantLeap;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leap Extras' Instant Open: the real leap menu opening, and its heads arriving, so a leap picked on the stand-in is
 * clicked the moment it can be ({@link InstantLeap}). On the game thread, after vanilla has applied each.
 */
@Mixin(ClientPacketListener.class)
public class InstantLeapPacketsMixin {
    @Inject(method = "handleOpenScreen", at = @At("RETURN"), require = 0)
    private void cc$opened(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        try { InstantLeap.onOpened(); } catch (Throwable ignored) { }
    }

    @Inject(method = "handleContainerContent", at = @At("RETURN"), require = 0)
    private void cc$contents(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        try { InstantLeap.onContents(); } catch (Throwable ignored) { }
    }

    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"), require = 0)
    private void cc$slot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        try { InstantLeap.onContents(); } catch (Throwable ignored) { }
    }
}
