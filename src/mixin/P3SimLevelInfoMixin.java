package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P3 Sim: the sim world reports itself as main's does in the login/respawn info: not flat, sea level 63
 * (the void generator would say flat and -63). Only the sim's own level; every other world is untouched.
 * The client's horizon (63 instead of the world's floor) follows from it, as on Hypixel.
 */
@Mixin(ServerLevel.class)
public class P3SimLevelInfoMixin {
    @Inject(method = "isFlat", at = @At("HEAD"), cancellable = true)
    private void ec$simNotFlat(CallbackInfoReturnable<Boolean> cir) {
        if (SimServer.INSTANCE.isSimLevel((ServerLevel) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "getSeaLevel", at = @At("HEAD"), cancellable = true)
    private void ec$simSeaLevel(CallbackInfoReturnable<Integer> cir) {
        if (SimServer.INSTANCE.isSimLevel((ServerLevel) (Object) this)) cir.setReturnValue(63);
    }
}
