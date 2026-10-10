package com.coffeeclient.mixin;

import com.coffeeclient.misc.OldBlocking;
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

/**
 * Old Blocking: the first-person sword drawn in 1.8.9's blocking pose. Only the pose stack of the
 * held item's drawing is touched - after the arm transform the blocking pose, and no swing while
 * blocking (1.8.9 drew none).
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class OldBlockingMixin {

    @WrapOperation(method = "submitArmWithItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;applyItemArmTransform(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/HumanoidArm;F)V"))
    private void cc$blockPose(FirstPersonHandsAndItemsRenderer self, PoseStack pose, HumanoidArm arm, float equip, Operation<Void> original,
                              @Local(argsOnly = true) ItemStack stack, @Local(argsOnly = true) InteractionHand hand) {
        original.call(self, pose, arm, equip);
        if (OldBlocking.blocking(stack, hand)) pose.mulPose(OldBlocking.pose(arm));
    }

    @WrapOperation(method = "submitArmWithItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;swingArm(FLcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/entity/HumanoidArm;)V"))
    private void cc$noSwingWhileBlocking(FirstPersonHandsAndItemsRenderer self, float attack, PoseStack pose, int side, HumanoidArm arm, Operation<Void> original,
                                         @Local(argsOnly = true) ItemStack stack, @Local(argsOnly = true) InteractionHand hand) {
        if (!OldBlocking.blocking(stack, hand)) original.call(self, attack, pose, side, arm);
    }
}
