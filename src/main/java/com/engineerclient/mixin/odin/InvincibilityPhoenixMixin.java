package com.engineerclient.mixin.odin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Odin's Invincibility Timer shows Phoenix as invincible for 4 s (80 ticks, the Lvl 100 pet's lore),
 * but in game a Phoenix proc covers you no longer than a mask's 3 s: in the Better PF recordings,
 * with the Phoenix kept out and no leap, the next Goldor death tick 60 server ticks later always
 * takes the next mask or kills you (analysis/masks). So the gold timer runs 3 s, like Spirit and Bonzo.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.InvincibilityTimer$InvincibilityType", remap = false)
public class InvincibilityPhoenixMixin {
    @Shadow(remap = false) @Final @Mutable private int maxInvincibilityTime;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ec$phoenix(CallbackInfo ci) {
        if ("PHOENIX".equals(((Enum<?>) (Object) this).name())) maxInvincibilityTime = 60;
    }
}
