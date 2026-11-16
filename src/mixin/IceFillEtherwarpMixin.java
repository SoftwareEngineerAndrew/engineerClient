package com.engineerclient.mixin;

import com.odtheking.odin.features.impl.dungeon.puzzlesolvers.IceFillSolver;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class IceFillEtherwarpMixin {

    @Inject(method = "startUseItem()V", at = @At("HEAD"), cancellable = true)
    private void ec$iceFillEtherwarp(CallbackInfo ci) {
        if (IceFillSolver.blocksEtherwarp()) ci.cancel();
    }
}
