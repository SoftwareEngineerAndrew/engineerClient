package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Portal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The F7 arena has nether portal blocks as decoration, and the P3 Sim world has no Nether: an arrow
 * (or you) going into one crashed the integrated server looking for the other side. In the sim
 * world nothing enters a portal; everywhere else this does nothing.
 */
@Mixin(Entity.class)
public class PortalSimMixin {
    @Inject(method = "setAsInsidePortal", at = @At("HEAD"), cancellable = true)
    private void ec$noPortalsInSim(Portal portal, BlockPos pos, CallbackInfo ci) {
        if (SimServer.isSimLevel(((Entity) (Object) this).level())) ci.cancel();
    }
}
