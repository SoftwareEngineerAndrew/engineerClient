package com.engineerclient.mixin;

import com.adm.features.impl.dungeon.puzzlesolvers.IceFillSolver;
import com.adm.features.impl.dungeon.puzzlesolvers.PuzzleArrows;
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
        if (IceFillSolver.blocksEtherwarp() || PuzzleArrows.blocksUse()) ci.cancel();
    }

    @Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
    private void ec$puzzleAttack(CallbackInfoReturnable<Boolean> cir) {
        if (PuzzleArrows.blocksAttack()) cir.setReturnValue(false);
    }
}
