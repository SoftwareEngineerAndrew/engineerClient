package com.engineerclient.mixin;

import com.engineerclient.p3sim.P3Sim;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A spectator sees invisible entities see-through; in the P3 Sim (Spectate Route watches as a spectator) that showed the
 * bots' invisible armor stands. There, invisible stays invisible to a spectator too.
 */
@Mixin(Entity.class)
public abstract class SpectatorHidesInvisibleMixin {
    @Shadow
    public abstract boolean isInvisible();

    @Inject(method = "isInvisibleTo", at = @At("HEAD"), cancellable = true)
    private void ads$hideFromSpectator(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (player.isSpectator() && P3Sim.INSTANCE.getInSim()) {
            cir.setReturnValue(isInvisible());
        }
    }
}
