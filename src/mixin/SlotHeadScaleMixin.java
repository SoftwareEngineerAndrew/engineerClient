package com.engineerclient.mixin;

import com.engineerclient.skyblock.ItemTweaks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiGraphicsExtractor.class)
public class SlotHeadScaleMixin {
    @Shadow @Final private Matrix3x2fStack pose;

    @WrapOperation(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;III)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V"))
    private void ec$headScale(GuiGraphicsExtractor self, LivingEntity owner, Level level, ItemStack stack, int x, int y, int seed, Operation<Void> original) {
        if (!ItemTweaks.smallHead(stack)) {
            original.call(self, owner, level, stack, x, y, seed);
            return;
        }
        // 14px instead of 16, kept centred in the slot.
        pose.pushMatrix();
        pose.translate(x * 0.125f + 1f, y * 0.125f + 1f);
        pose.scale(0.875f);
        original.call(self, owner, level, stack, x, y, seed);
        pose.popMatrix();
    }
}
