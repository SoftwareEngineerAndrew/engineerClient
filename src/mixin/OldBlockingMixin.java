package com.engineerclient.mixin;

import com.engineerclient.render.ItemAnimations;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class OldBlockingMixin {

    @WrapOperation(method = "submitArmWithItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;applyItemArmTransform(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/HumanoidArm;F)V"))
    private void ec$blockPose(FirstPersonHandsAndItemsRenderer self, PoseStack pose, HumanoidArm arm, float equip, Operation<Void> original,
                              @Local(argsOnly = true) ItemStack stack, @Local(argsOnly = true) InteractionHand hand) {
        original.call(self, pose, arm, equip);
        if (ItemAnimations.blocking(stack, hand)) pose.mulPose(ItemAnimations.blockPose(arm));
    }

    @WrapOperation(method = "submitArmWithItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;swingArm(FLcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/entity/HumanoidArm;)V"))
    private void ec$noSwingWhileBlocking(FirstPersonHandsAndItemsRenderer self, float attack, PoseStack pose, int side, HumanoidArm arm, Operation<Void> original,
                                         @Local(argsOnly = true) ItemStack stack, @Local(argsOnly = true) InteractionHand hand) {
        if (!ItemAnimations.blocking(stack, hand)) original.call(self, attack, pose, side, arm);
    }
}
