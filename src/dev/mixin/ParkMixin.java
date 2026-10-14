package com.devgineerclient.mixin;

import com.devgineerclient.misc.Park;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.util.TimeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Parked start (Park): the last stop before SDL, the window and the GPU come up. */
@Mixin(RenderSystem.class)
public class ParkMixin {

    @Inject(method = "initBackendSystem", at = @At("HEAD"))
    private static void dc$park(CallbackInfoReturnable<TimeSource.NanoTimeSource> cir) {
        Park.atParkPoint();
    }
}
