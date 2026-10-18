package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The ADS world's chunks are not written when its level closes either (see AdsEngineNoSaveMixin). */
@Mixin(ServerChunkCache.class)
public class AdsEngineNoChunkFlushMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "save(Z)V", at = @At("HEAD"), cancellable = true)
    private void ec$adsNoFlush(boolean flush, CallbackInfo ci) {
        if (SimServer.isSimLevel(level)) ci.cancel();
    }
}
