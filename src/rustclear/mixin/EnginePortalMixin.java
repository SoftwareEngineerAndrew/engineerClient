package net.rustclear.mod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Portal;
import net.rustclear.mod.RustclearMod;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nothing in the Rustclear world goes through a portal. Without this, P5's relic stands were
 * removed as CHANGED_DIMENSION in their first tick (found by the Phase 9 LAN check), so no player
 * could see or click a relic; players are kept out of the blood room's nether portal the same way.
 */
@Mixin(Entity.class)
public class EnginePortalMixin {
    @Unique
    private static boolean rustclear$logged;

    @Inject(method = "setAsInsidePortal", at = @At("HEAD"), cancellable = true)
    private void rustclear$noPortals(Portal portal, BlockPos pos, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!self.level().isClientSide() && EngineServer.isEngineLevel(self.level())) {
            if (!rustclear$logged) {
                rustclear$logged = true;
                RustclearMod.LOGGER.info("Rustclear world: kept {} out of {} at {}", self.getType().toShortString(),
                    self.level().getBlockState(pos).getBlock().getDescriptionId(), pos);
            }
            ci.cancel();
        }
    }
}
