package com.engineerclient.mixin;

import com.engineerclient.skyblock.ItemTweaks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemStack.class)
public class OldMasterStarMixin {
    @ModifyReturnValue(method = "getHoverName", at = @At("RETURN"))
    private Component ec$oldMasterStars(Component name) {
        return ItemTweaks.hoverName(name);
    }
}
