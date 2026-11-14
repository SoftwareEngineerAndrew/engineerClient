package com.engineerclient.mixin;

import com.engineerclient.misc.ItemHighlight;
import com.engineerclient.misc.RandomStuff;
import com.engineerclient.skyblock.Arrows;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hide Own Arrows Nearby in {@link Arrows}: an arrow the local player shot is not drawn while
 * it flies within 5 blocks of them. Arrows in item frames are items, so they never reach here.
 * Also hides the dropped items {@link ItemHighlight} draws a box for instead.
 */
@Mixin(EntityRenderDispatcher.class)
public class OwnArrowsHideMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void ec$hideOwnArrows(E entity, Frustum frustum, double x, double y, double z, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof AbstractArrow arrow
            && Arrows.INSTANCE.hidesOwnArrow(arrow, ((ArrowInGroundInvoker) arrow).ec$isInGround())) cir.setReturnValue(false);
        if (entity instanceof ItemEntity item && ItemHighlight.INSTANCE.hides(item)) cir.setReturnValue(false);
        if (entity instanceof Sheep && RandomStuff.INSTANCE.hidesSheep()) cir.setReturnValue(false);
    }
}
