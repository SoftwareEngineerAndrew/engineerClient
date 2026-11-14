package com.engineerclient.mixin;

import com.engineerclient.skyblock.ItemTweaks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphicsExtractor.class)
public class PlayerHeadSizeMixin {
    @Shadow @Final private Matrix3x2fStack pose;

    @Inject(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"))
    private void ec$shrinkHead(LivingEntity owner, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
        if (!ItemTweaks.INSTANCE.smallHead(stack)) return;
        float scale = 0.875f;
        int offset = (16 - (int) (scale * 16)) / 2;
        pose.pushMatrix();
        pose.translate(x * (1 - scale) + offset, y * (1 - scale) + offset);
        pose.scale(scale);
    }

    @Inject(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;III)V", at = @At("TAIL"))
    private void ec$restoreHead(LivingEntity owner, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
        if (ItemTweaks.INSTANCE.smallHead(stack)) pose.popMatrix();
    }
}
