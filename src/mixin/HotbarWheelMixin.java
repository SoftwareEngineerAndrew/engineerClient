package com.engineerclient.mixin;

import com.engineerclient.skyblock.ItemTweaks;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.MouseHandler;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MouseHandler.class)
public class HotbarWheelMixin {
    @WrapWithCondition(method = "onScroll(JDD)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;setSelectedSlot(I)V"))
    private boolean ec$wheelSelects(Inventory inventory, int slot) {
        return !ItemTweaks.lockHotbarWheel();
    }
}
