package com.engineerclient.mixin;

import com.engineerclient.p3sim.clear.replay.ReplayClient;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While an ADS replay drives your player your game sends none of its own moves or clicks: the server gets the recording's. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class AdsReplaySendMixin {
    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void ec$replaySend(Packet<?> packet, CallbackInfo ci) { if (ReplayClient.blocks(packet)) ci.cancel(); }
}
