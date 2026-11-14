package com.odtheking.mixin.mixins;

import com.engineerclient.render.RenderCleanup;
import com.odtheking.odin.features.impl.dungeon.puzzlesolvers.PuzzleSolvers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private void onRender(T entity, Frustum culler, double camX, double camY, double camZ, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;

        if (shouldHideDeadEntity(entity) ||
                PuzzleSolvers.shouldHideBlaze(entity) ||
                !RenderCleanup.INSTANCE.shouldRenderPlayer(entity) ||
                shouldHideDyingArmorStand(entity)
        ) cir.setReturnValue(false);
    }

    @Unique
    private static boolean shouldHideDeadEntity(Entity entity) {
        return RenderCleanup.INSTANCE.hidesDeathAnimation() && entity instanceof LivingEntity living && living.isDeadOrDying();
    }

    @Unique
    private static boolean shouldHideDyingArmorStand(Entity entity) {
        if (!RenderCleanup.INSTANCE.hidesDyingArmorStands() || !(entity instanceof ArmorStand armorStand)) return false;
        return armorStand.level().getEntity(armorStand.getId() - 1) instanceof LivingEntity living && living.isDeadOrDying();
    }
}

