package com.engineerclient.mixin;

import com.engineerclient.p3sim.SpectateRoute;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The P3 Sim's Spectate Route: sneaking doesn't take you off the bot you watch (vanilla's spectator sneak leaves the
 * entity you spectate: ServerPlayer's tick asks Player's wantsToStopRiding). The menu's Stop or Restart P3 ends the
 * route. Only for the one watching, on the sim's server.
 */
@Mixin(Player.class)
public class SpectateRouteSneakMixin {
    @Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
    private void ec$stayOnBot(CallbackInfoReturnable<Boolean> cir) {
        if (SpectateRoute.holds((Player) (Object) this)) cir.setReturnValue(false);
    }
}
