package com.engineerclient.mixin.odin;

import com.engineerclient.lcm.LcmMapInfo;
import kotlin.Pair;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Odin's Map Info Full Hud (the lambda behind its "Full Hud" HUD setting, which draws the score, secrets, crypts
 * and the rest and returns its size): with Map Info's LCM Style on, {@link LcmMapInfo} draws it instead.
 * If it can't draw a frame, Odin's own is drawn.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.MapInfo$fullHud$2", remap = false)
public class LcmMapInfoMixin {

    @Inject(method = "invoke(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Z)Lkotlin/Pair;", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ec$lcmMapInfo(GuiGraphicsExtractor g, boolean example, CallbackInfoReturnable<Pair<Integer, Integer>> cir) {
        if (!LcmMapInfo.active()) return;
        Pair<Integer, Integer> size = LcmMapInfo.render(g, example);
        if (size != null) cir.setReturnValue(size);
    }
}
