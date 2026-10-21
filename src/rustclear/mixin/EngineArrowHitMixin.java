package net.rustclear.mod.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon mobs are invulnerable to vanilla damage, so a player's arrow would bounce off them. In the
 * Rustclear world the run takes the hit instead (EngineServer.arrowHit). Everywhere else this does nothing.
 */
@Mixin(AbstractArrow.class)
public class EngineArrowHitMixin {
    @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true)
    private void rustclear$engineArrowHit(EntityHitResult hit, CallbackInfo ci) {
        if (EngineServer.arrowHit((AbstractArrow) (Object) this, hit.getEntity())) {
            ci.cancel();
        }
    }

    /** An arrow that lands in a block is gone at once in the Rustclear world (none left lying around). */
    @Inject(method = "onHitBlock", at = @At("TAIL"))
    private void rustclear$engineArrowLanded(BlockHitResult hit, CallbackInfo ci) {
        AbstractArrow arrow = (AbstractArrow) (Object) this;
        if (EngineServer.isEngineLevel(arrow.level())) {
            arrow.discard();
        }
    }
}
