package com.coffeeclient.mixin;

import com.coffeeclient.misc.NoPack;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No Pack (NoPack): a SkyBlock item's Hypixel pack model swapped for its look before the pack. */
@Mixin(ItemModelResolver.class)
public abstract class NoPackModelMixin {
    @WrapOperation(method = "appendItemLayers", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"))
    private Object cc$noPackModel(ItemStack stack, DataComponentType<?> type, Operation<Object> original) {
        Object current = original.call(stack, type);
        if (type != DataComponents.ITEM_MODEL || !(current instanceof Identifier id)) return current;
        return NoPack.model(stack, id);
    }
}
