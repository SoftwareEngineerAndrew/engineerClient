package com.engineerclient.mixin.odin;

import com.engineerclient.lcm.PaulScore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Map Info's Paul Score (On / Off) overrides Odin's Paul check, which its score, needed secrets and Full Hud all
 * read (DungeonUtils.isPaul and Map Info call DungeonListener.getPaul). Auto leaves Odin's mayor check.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.utils.skyblock.dungeon.DungeonListener", remap = false)
public class PaulScoreMixin {
    @Inject(method = "getPaul()Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ec$paulScore(CallbackInfoReturnable<Boolean> cir) {
        Boolean forced = PaulScore.forced();
        if (forced != null) cir.setReturnValue(forced);
    }
}
