package net.rustclear.mod.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In the Rustclear world a block hit goes to EngineServer.clientHit before adventure mode can drop
 * it (as engineerClient's P3 Sim does for its Dungeonbreaker). Everywhere else this does nothing.
 * The held slot goes out first, as vanilla's startDestroyBlock sends it: a guest who scrolled to the
 * Superboom and hit a gate in the same tick was otherwise still holding the old item on the host.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class EngineBlockHitMixin {
    @Shadow
    protected abstract void ensureHasSentCarriedItem();

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void rustclear$engineHit(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (net.rustclear.mod.engine.Engine.isEngineGuest()) {
            ensureHasSentCarriedItem();
        }
        if (EngineServer.clientHit(pos)) {
            cir.setReturnValue(false);
        }
    }
}
