package com.adm.mixin.mixins;

import com.adm.features.impl.boss.DragonTintState;
import com.adm.features.impl.boss.WitherDragons;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnderDragonRenderer.class)
public class EnderDragonRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/boss/enderdragon/EnderDragon;Lnet/minecraft/client/renderer/entity/state/EnderDragonRenderState;F)V", at = @At("TAIL"))
    private void ec$extractTint(EnderDragon dragon, EnderDragonRenderState state, float partialTicks, CallbackInfo ci) {
        ((DragonTintState) state).ec$setTint(WitherDragons.tintOf(dragon));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/EnderDragonRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/resources/Identifier;III)V")
    )
    private void ec$tintModel(SubmitNodeCollector collector, Model model, Object state, PoseStack pose, Identifier texture, int light, int overlay, int outline, Operation<Void> original) {
        int tint = state instanceof DragonTintState tinted ? tinted.ec$tint() : 0;
        if (tint == 0) original.call(collector, model, state, pose, texture, light, overlay, outline);
        else collector.submitModel(model, state, pose, model.renderType(texture), light, overlay, tint, null, outline);
    }
}
