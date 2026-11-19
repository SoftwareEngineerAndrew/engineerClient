package com.adm.mixin.mixins;

import com.engineerclient.render.Visibility;
import com.adm.features.impl.dungeon.Highlight;
import com.adm.features.impl.dungeon.puzzlesolvers.PuzzleSolvers;
import com.engineerclient.misc.Zpew;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

    @ModifyReturnValue(method = "shouldRender", at = @At("RETURN"))
    private boolean onRender(boolean shown, T entity, Frustum culler, double camX, double camY, double camZ, float partialTicks) {
        // Legit Zpew: your own model is drawn where the camera is, never culled from its old spot.
        if (entity == Minecraft.getInstance().player && Zpew.cameraPos() != null) return true;
        if (!shown) return false;

        return !(shouldHideDeadEntity(entity) ||
                PuzzleSolvers.shouldHideBlaze(entity) ||
                com.adm.features.impl.boss.LividSolver.hidesWrong(entity) ||
                Highlight.hidesTag(entity) ||
                com.engineerclient.render.ScoreboardNametags.hidesMobDialogue(entity) ||
                com.engineerclient.render.Visibility.hidesDamageNumber(entity) ||
                !Visibility.INSTANCE.shouldRenderPlayer(entity) ||
                shouldHideDyingArmorStand(entity));
    }

    @Unique
    private static boolean shouldHideDeadEntity(Entity entity) {
        return Visibility.INSTANCE.hidesDeathAnimation() && entity instanceof LivingEntity living && living.isDeadOrDying();
    }

    @Unique
    private static boolean shouldHideDyingArmorStand(Entity entity) {
        if (!Visibility.INSTANCE.hidesDyingArmorStands() || !(entity instanceof ArmorStand armorStand)) return false;
        return armorStand.level().getEntity(armorStand.getId() - 1) instanceof LivingEntity living && living.isDeadOrDying();
    }
}

