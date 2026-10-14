package com.devgineerclient.mixin.odin;

import com.devgineerclient.practice.I4Aims;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Odin's Arrows Device works its aim positions out for a Terminator only. With a shortbow in hand and
 * i4 Aim (Devgineer Client) on, it gets the ones for that bow instead ({@link I4Aims}) (the Mosquito, Terror's Hydra arrows).
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.boss.ArrowsDevice", remap = false)
public class ArrowsDeviceAimMixin {
    @Inject(method = "calculateOptimalAimPositions(Lnet/minecraft/core/BlockPos;)Ljava/util/List;", at = @At("HEAD"), cancellable = true, remap = false)
    private void dc$aimsForYourBow(BlockPos target, CallbackInfoReturnable<List<?>> cir) {
        try {
            List<Object> aims = I4Aims.aimsFor(target);
            if (aims != null) cir.setReturnValue(aims);
        } catch (Throwable ignored) {
            // Odin's own, then.
        }
    }
}
