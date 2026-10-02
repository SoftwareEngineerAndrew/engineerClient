package com.engineerclient.mixin;

import com.engineerclient.recorder.WorldCapture;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: a map's whole picture once the client has applied a map packet (`mapfull`).
 *
 * handleMapItemData first hops to the game thread (PacketUtils.ensureRunningOnSameThread throws on
 * the network thread before the method ends), so TAIL only runs on the game thread, with the patch
 * applied to the map. Optional (require = 0): if the target ever moves, the recorder only loses
 * this line; the packet line still has the patch.
 */
@Mixin(ClientPacketListener.class)
public class MapAppliedMixin {

    @Inject(
        method = "handleMapItemData(Lnet/minecraft/network/protocol/game/ClientboundMapItemDataPacket;)V",
        at = @At("TAIL"),
        require = 0,
        expect = 0
    )
    private void ec$mapApplied(ClientboundMapItemDataPacket packet, CallbackInfo ci) {
        try {
            WorldCapture.mapApplied(packet);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }
}
