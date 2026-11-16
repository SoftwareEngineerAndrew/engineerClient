package com.engineerclient.mixin.core;

import com.engineerclient.lcm.PaulScore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clear Info's Paul Score (On / Off) overrides ADM's Paul check, which its score and needed secrets
 * read (DungeonUtils.isPaul calls DungeonListener.getPaul). Auto leaves ADM's mayor check.
 */
@Pseudo
@Mixin(targets = "com.adm.utils.skyblock.dungeon.DungeonListener", remap = false)
public class PaulScoreMixin {
    @Inject(method = "getPaul()Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ec$paulScore(CallbackInfoReturnable<Boolean> cir) {
        Boolean forced = PaulScore.forced();
        if (forced != null) cir.setReturnValue(forced);
    }
}
