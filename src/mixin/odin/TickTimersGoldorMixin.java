package com.engineerclient.mixin.odin;

import com.engineerclient.misc.OdinTickTimers;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.odtheking.odin.features.impl.boss.TickTimers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Odin's Goldor Hud (TickTimers$goldorHud$2) formatting its timer, preview included: with Goldor
 * Count Up on, the Tick timer is formatted counting up ({@link OdinTickTimers#tick}). The colour
 * goes in as formatTimer's own override, so the mask bit that defaults it is cleared.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.boss.TickTimers$goldorHud$2", remap = false)
public class TickTimersGoldorMixin {
    private static final String OWNER = "Lcom/odtheking/odin/features/impl/boss/TickTimers;";

    @WrapOperation(
        method = "invoke(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Z)Lkotlin/Pair;",
        at = @At(value = "INVOKE", target = OWNER + "formatTimer$default(" + OWNER + "IILjava/lang/String;Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/String;"),
        remap = false
    )
    private String ec$goldorCountUp(TickTimers instance, int time, int max, String prefix, String colour, int mask, Object marker, Operation<String> original) {
        return OdinTickTimers.tick(time, max, prefix, (t, m, p, c) ->
            c == null ? original.call(instance, t, m, p, colour, mask, marker) : original.call(instance, t, m, p, c, mask & ~8, marker));
    }
}
