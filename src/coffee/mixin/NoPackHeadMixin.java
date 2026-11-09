package com.coffeeclient.mixin;

import com.coffeeclient.misc.NoPack;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.special.PlayerHeadSpecialRenderer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No Pack (NoPack): a SkyBlock item that was a head before the pack is drawn with that head's texture. */
@Mixin(PlayerHeadSpecialRenderer.class)
public abstract class NoPackHeadMixin {
    @WrapOperation(method = "extractArgument(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/client/renderer/PlayerSkinRenderCache$RenderInfo;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"))
    private Object cc$noPackHead(ItemStack stack, DataComponentType<?> type, Operation<Object> original) {
        Object current = original.call(stack, type);
        if (type != DataComponents.PROFILE) return current;
        return NoPack.profile(stack, (ResolvableProfile) current);
    }
}
