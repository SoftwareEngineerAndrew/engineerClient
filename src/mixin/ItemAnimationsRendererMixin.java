package com.engineerclient.mixin;

import com.engineerclient.render.ItemAnimations;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class ItemAnimationsRendererMixin {
    @ModifyExpressionValue(method = "submitHandsWithItems", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;swingAnimation:F", opcode = Opcodes.GETFIELD))
    private float ec$swing(float vanilla, @Local(argsOnly = true, ordinal = 0) float partialTicks) {
        return ItemAnimations.swing(vanilla, partialTicks);
    }

    @Inject(method = "submitHandsWithItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V", ordinal = 0))
    private void ec$transformHeld(CallbackInfo ci, @Local(argsOnly = true) PoseStack pose) {
        ItemAnimations.transformHeld(pose);
    }

    @WrapWithCondition(method = "submitHandsWithItems", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V"))
    private boolean ec$handSway(PoseStack pose, Axis axis, float degrees) {
        return !ItemAnimations.noHandSway();
    }

    @Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void ec$scaleItem(CallbackInfo ci, @Local(argsOnly = true) PoseStack pose) {
        ItemAnimations.scaleItem(pose);
    }

    @Inject(method = "renderPlayerHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;getRenderer(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/client/renderer/entity/player/AvatarRenderer;"))
    private void ec$scaleHand(CallbackInfo ci, @Local(argsOnly = true) PoseStack pose) {
        ItemAnimations.scaleHand(pose);
    }

    @Inject(method = "renderMapHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;renderPlayerHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V"))
    private void ec$scaleMapHand(CallbackInfo ci, @Local(argsOnly = true) PoseStack pose) {
        ItemAnimations.scaleMapHand(pose);
    }

    @Inject(method = "renderMap", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V"))
    private void ec$scaleMap(CallbackInfo ci, @Local(argsOnly = true) PoseStack pose) {
        ItemAnimations.scaleMap(pose);
    }

    @ModifyExpressionValue(method = "renderPlayerArm", at = {
        @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;sin(D)F", ordinal = 0),
        @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;sin(D)F", ordinal = 1),
        @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;sin(D)F", ordinal = 2)
    })
    private float ec$armSwingMove(float wave) {
        return ItemAnimations.noSwingMove() ? 0f : wave;
    }

    @WrapWithCondition(method = "swingArm", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private boolean ec$itemSwingMove(PoseStack pose, float x, float y, float z) {
        return !ItemAnimations.noSwingMove();
    }

    @WrapWithCondition(method = "renderOneHandedMap", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 2))
    private boolean ec$mapSwingMove(PoseStack pose, float x, float y, float z) {
        return !ItemAnimations.noSwingMove();
    }

    @WrapWithCondition(method = "renderTwoHandedMap", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 0))
    private boolean ec$twoHandMapSwingMove(PoseStack pose, float x, float y, float z) {
        return !ItemAnimations.noSwingMove();
    }
}
