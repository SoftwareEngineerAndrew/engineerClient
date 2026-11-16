package com.engineerclient.mixin;

import com.engineerclient.misc.ItemHighlight;
import com.engineerclient.render.Visibility;
import com.engineerclient.skyblock.Arrows;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderDispatcher.class)
public class OwnArrowsHideMixin {
    @ModifyReturnValue(method = "shouldRender", at = @At("RETURN"))
    private <E extends Entity> boolean ec$hideEntities(boolean shown, E entity, Frustum frustum, double x, double y, double z, float partialTicks) {
        if (!shown) return false;
        if (entity instanceof AbstractArrow arrow) {
            boolean inGround = ((ArrowInGroundInvoker) arrow).ec$isInGround();
            return !(Arrows.INSTANCE.hidesGroundedArrows() && inGround || Arrows.INSTANCE.hidesOwnArrow(arrow, inGround));
        }
        if (entity instanceof ItemEntity item) return !ItemHighlight.INSTANCE.hides(item);
        if (entity instanceof Sheep) return !Visibility.INSTANCE.hidesSheep();
        return true;
    }
}
