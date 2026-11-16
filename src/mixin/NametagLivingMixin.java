package com.engineerclient.mixin;

import com.engineerclient.render.ScoreboardNametags;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntityRenderer.class)
public class NametagLivingMixin {
    @ModifyReturnValue(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"))
    private boolean ec$hideDinnerbone(boolean shown, LivingEntity entity, double distanceSq) {
        return shown && !ScoreboardNametags.INSTANCE.hidesDinnerbone(entity);
    }
}
