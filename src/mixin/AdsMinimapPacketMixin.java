package com.engineerclient.mixin;

import com.engineerclient.ads.map.MapScan;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ADS's minimap: every map packet, once the game has applied it. handleMapItemData first hops to the game thread,
 * so TAIL only runs there, and the minimap's scan never races the game.
 */
@Mixin(ClientPacketListener.class)
public class AdsMinimapPacketMixin {

    @Inject(method = "handleMapItemData(Lnet/minecraft/network/protocol/game/ClientboundMapItemDataPacket;)V", at = @At("TAIL"), require = 0)
    private void ads$minimap(ClientboundMapItemDataPacket packet, CallbackInfo ci) {
        try {
            MapScan.onMapPacket(packet);
        } catch (Throwable t) {
            // the map stays as it was
        }
    }
}
