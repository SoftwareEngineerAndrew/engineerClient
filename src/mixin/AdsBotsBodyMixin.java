package com.engineerclient.mixin;

import com.engineerclient.p3sim.clear.bots.BotRegistry;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The ADS teammate bots reach the client as real player entities (so Odin's teammate binding and class glow see them), which makes them
 * pickable like any player. In the sim they touch nothing, as the boss sim's mannequins do: the crosshair, clicks and arrows go through them.
 * Pushing is the scoreboard team's job (collision rule never); only the pick needs this. A bot is told apart from a real player by the profile
 * id in {@link BotRegistry}, which holds the run's bots and nothing else.
 */
@Mixin(Player.class)
public class AdsBotsBodyMixin {
    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
    private void ads$botsAreNotPickable(CallbackInfoReturnable<Boolean> cir) {
        if (BotRegistry.isBot(((Player) (Object) this).getUUID())) cir.setReturnValue(false);
    }
}
