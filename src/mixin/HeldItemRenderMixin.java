package com.engineerclient.mixin;

import com.engineerclient.render.ItemAnimations;
import com.engineerclient.skyblock.ItemTweaks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class HeldItemRenderMixin {
    @ModifyExpressionValue(method = "submitHandsWithItems", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;swingAnimation:F", opcode = Opcodes.GETFIELD))
    private float ec$frame(float swing, @Local(argsOnly = true, ordinal = 0) float partialTicks) {
        return ItemAnimations.beginFrame(swing, partialTicks);
    }

    @WrapWithCondition(method = "submitHandsWithItems", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V"))
    private boolean ec$sway(PoseStack pose, Axis axis, float degrees) {
        return ItemAnimations.keepSway();
    }

    @ModifyArg(method = "submitHandsWithItems", index = 8, at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private PoseStack ec$placeHeld(PoseStack pose) {
        return ItemAnimations.placeHeld(pose);
    }

    @ModifyExpressionValue(method = "submitArmWithItem", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;currentSwing:Lnet/minecraft/world/entity/LivingEntity$SwingDescription;", opcode = Opcodes.GETFIELD))
    private LivingEntity.SwingDescription ec$block(LivingEntity.SwingDescription swing, @Local(argsOnly = true) PoseStack pose, @Local HumanoidArm arm,
                                                   @Local(argsOnly = true) ItemStack stack, @Local(argsOnly = true) InteractionHand hand) {
        return ItemAnimations.blockPose(pose, arm, stack, hand) ? null : swing;
    }

    @ModifyArg(method = "submitArmWithItem", index = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private PoseStack ec$sizeItem(PoseStack pose) {
        return ItemAnimations.sizeItem(pose);
    }

    @ModifyArg(method = "submitArmWithItem", index = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private PoseStack ec$smallHead(PoseStack pose, @Local(argsOnly = true) ItemStack stack) {
        if (ItemTweaks.smallHead(stack)) pose.scale(0.875f, 0.875f, 0.875f);
        return pose;
    }

    @ModifyArg(method = "renderPlayerHand", index = 0, at = {
        @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/player/AvatarRenderer;renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V"),
        @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/player/AvatarRenderer;renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V")
    })
    private PoseStack ec$sizeHand(PoseStack pose) {
        return ItemAnimations.sizeHand(pose);
    }

    @ModifyArg(method = "renderMapHand", index = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;renderPlayerHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V"))
    private PoseStack ec$sizeMapHand(PoseStack pose) {
        return ItemAnimations.sizeMapHand(pose);
    }

    @ModifyArg(method = "renderMap", index = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V"))
    private PoseStack ec$sizeMap(PoseStack pose) {
        return ItemAnimations.sizeMap(pose);
    }

    @ModifyExpressionValue(method = "renderPlayerArm",
        slice = @Slice(to = @At(value = "INVOKE", ordinal = 0, target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V")),
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;sin(D)F"))
    private float ec$armReach(float wave) {
        return ItemAnimations.staticSwing() ? 0f : wave;
    }

    @WrapOperation(method = "swingArm", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void ec$itemReach(PoseStack pose, float x, float y, float z, Operation<Void> original) {
        if (ItemAnimations.staticSwing()) return;
        original.call(pose, x * ItemAnimations.swingX(), y * ItemAnimations.swingY(), z * ItemAnimations.swingZ());
    }

    @WrapWithCondition(method = "renderOneHandedMap", at = @At(value = "INVOKE", ordinal = 2, target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private boolean ec$oneHandMapReach(PoseStack pose, float x, float y, float z) {
        return !ItemAnimations.staticSwing();
    }

    @WrapWithCondition(method = "renderTwoHandedMap", at = @At(value = "INVOKE", ordinal = 0, target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private boolean ec$twoHandMapReach(PoseStack pose, float x, float y, float z) {
        return !ItemAnimations.staticSwing();
    }
}
