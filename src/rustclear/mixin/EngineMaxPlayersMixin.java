package net.rustclear.mod.mixin;

import net.minecraft.client.server.IntegratedServer;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A singleplayer world lets in 8 players; a Rustclear party can be set larger than that, so the
 * host's world allows the most a party can hold, and the party's own limit (Party.refusal) turns
 * away anyone past the leader's setting.
 */
@Mixin(IntegratedServer.class)
public class EngineMaxPlayersMixin {
    @Inject(method = "getMaxPlayers", at = @At("HEAD"), cancellable = true)
    private void rustclear$partySize(CallbackInfoReturnable<Integer> cir) {
        if (EngineServer.server() == (Object) this) {
            cir.setReturnValue(EngineServer.MAX_PLAYERS);
        }
    }
}
