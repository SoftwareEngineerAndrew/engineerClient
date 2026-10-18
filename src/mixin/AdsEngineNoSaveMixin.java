package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The ADS world is never saved: it is deleted and built again on every open (SimWorld), so writing its chunks (the whole dungeon, at the
 * pause menu's autosave and again on leaving) is time spent for nothing. Every other world saves as usual.
 */
@Mixin(MinecraftServer.class)
public class AdsEngineNoSaveMixin {
    @Inject(method = "saveAllChunks(ZZZ)Z", at = @At("HEAD"), cancellable = true)
    private void ec$adsNoChunkSave(boolean silent, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (SimServer.INSTANCE.getServer() == (Object) this) cir.setReturnValue(true);
    }

    @Inject(method = "saveEverything(ZZZ)Z", at = @At("HEAD"), cancellable = true)
    private void ec$adsNoSave(boolean silent, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (SimServer.INSTANCE.getServer() == (Object) this) cir.setReturnValue(true);
    }
}
