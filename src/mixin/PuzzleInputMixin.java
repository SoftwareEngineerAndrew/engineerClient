package com.engineerclient.mixin;

import com.odtheking.odin.features.impl.dungeon.puzzlesolvers.BlazeSolver;
import com.odtheking.odin.features.impl.dungeon.puzzlesolvers.IceFillSolver;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class PuzzleInputMixin {

    @Inject(method = "startUseItem()V", at = @At("HEAD"), cancellable = true)
    private void ec$puzzleUse(CallbackInfo ci) {
        if (IceFillSolver.blocksEtherwarp() || BlazeSolver.blocksShortbow(false)) ci.cancel();
    }

    @Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
    private void ec$puzzleAttack(CallbackInfoReturnable<Boolean> cir) {
        if (BlazeSolver.blocksShortbow(true)) cir.setReturnValue(false);
    }
}
