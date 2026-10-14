package com.engineerclient.mixin;

import com.engineerclient.p3sim.Sim;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P3 Sim parties: every packet a player sends is handled as theirs ({@link Sim#enterPacket}), so a click in a
 * terminal, a block hit or an item use is that player's, not the host's. Only on the sim's server thread; the
 * netty-thread pass of each handler is ignored there.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class SimActorMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "/^handle/", at = @At("HEAD"), require = 0)
    private void ec$simActorIn(CallbackInfo ci) {
        Sim.enterPacket(this.player);
    }

    @Inject(method = "/^handle/", at = @At("RETURN"), require = 0)
    private void ec$simActorOut(CallbackInfo ci) {
        Sim.leavePacket(this.player);
    }
}
