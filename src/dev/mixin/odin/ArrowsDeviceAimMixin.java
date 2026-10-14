package com.devgineerclient.mixin.odin;

import com.devgineerclient.practice.I4Aims;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Odin's Arrows Device works its aim positions out for a Terminator only, as cubes. While i4 Aim ({@link I4Aims}) is on
 * it draws the spots for the bow you hold itself, so Odin gets none to draw; its target highlight stays.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.boss.ArrowsDevice", remap = false)
public class ArrowsDeviceAimMixin {
    @Inject(method = "calculateOptimalAimPositions(Lnet/minecraft/core/BlockPos;)Ljava/util/List;", at = @At("HEAD"), cancellable = true, remap = false)
    private void dc$aimsForYourBow(BlockPos target, CallbackInfoReturnable<List<?>> cir) {
        try {
            if (I4Aims.replacesOdinAims()) cir.setReturnValue(new ArrayList<>());
        } catch (Throwable ignored) {
            // Odin's own, then.
        }
    }
}
