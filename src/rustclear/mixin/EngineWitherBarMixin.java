package net.rustclear.mod.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The bosses are vanilla withers, as on Hypixel, but a run has one boss bar for its whole length
 * (RunBar, menus-ui.md): in the Rustclear world a wither never shows its own.
 */
@Mixin(WitherBoss.class)
public class EngineWitherBarMixin {
    @Inject(method = "startSeenByPlayer", at = @At("HEAD"), cancellable = true)
    private void rustclear$noOwnBar(ServerPlayer player, CallbackInfo ci) {
        if (EngineServer.isEngineLevel(((WitherBoss) (Object) this).level())) {
            ci.cancel();
        }
    }
}
