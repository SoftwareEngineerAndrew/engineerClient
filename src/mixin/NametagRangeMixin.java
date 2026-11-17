package com.engineerclient.mixin;

import com.engineerclient.render.ScoreboardNametags;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderer.class)
public abstract class NametagRangeMixin {
    // Your own tag and score line are kept at any camera distance.
    @ModifyExpressionValue(method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;square(D)D"))
    private double ec$ownRange(double rangeSq, @Local(argsOnly = true) Entity entity) {
        return ScoreboardNametags.ownTag(entity) ? Double.POSITIVE_INFINITY : rangeSq;
    }
}
