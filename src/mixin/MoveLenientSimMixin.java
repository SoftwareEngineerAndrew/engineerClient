package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P3 Sim: the server takes your move into a block it still has. The Dungeonbreaker breaks at once in your game but
 * only after the simulated ping on the server, so walking into the hole meant "moved wrongly" and a pull back (blocks
 * that felt like ghosts). Hypixel doesn't pull you back there either.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MoveLenientSimMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "isEntityCollidingWithAnythingNew", at = @At("HEAD"), cancellable = true)
    private void ec$lenientInSim(LevelReader level, Entity entity, AABB box, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (entity == this.player && SimServer.isSimLevel(this.player.level())) cir.setReturnValue(false);
    }
}
