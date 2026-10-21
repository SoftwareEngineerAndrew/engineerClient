package net.rustclear.mod.mixin;

import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P5's dragons fly where WitherKingPhase puts them: in the Rustclear world vanilla's dragon AI
 * (End podium targets, block breaking, wing knockback) never runs on the server. They are not
 * NoAI, because a NoAI dragon's aiStep returns early on the client too: no flight history, so its
 * body never turns or tilts, and its wings stop.
 */
@Mixin(EnderDragon.class)
public class EngineDragonMixin {
    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void rustclear$noServerAi(CallbackInfo ci) {
        EnderDragon self = (EnderDragon) (Object) this;
        if (!self.level().isClientSide() && EngineServer.isEngineLevel(self.level())) {
            ci.cancel();
        }
    }
}
