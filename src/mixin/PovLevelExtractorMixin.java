package com.engineerclient.mixin;

import com.engineerclient.pov.PovCapture;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A POV pass extracts the level from a teammate's eyes, but the section dirty-tracking grid stays
 * centred on YOUR camera: recentring it on every pass would mark its edge sections dirty, back and
 * forth, every frame.
 */
@Mixin(LevelExtractor.class)
public class PovLevelExtractorMixin {

    @WrapWithCondition(
        method = "extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/SectionUpdateTracker;repositionCamera(Lnet/minecraft/core/SectionPos;)V")
    )
    private boolean ec$keepTracker(SectionUpdateTracker tracker, SectionPos pos) {
        return !PovCapture.INSTANCE.getCapturing();
    }
}
