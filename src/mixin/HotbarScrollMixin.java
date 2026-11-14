package com.engineerclient.mixin;

import com.engineerclient.skyblock.ItemTweaks;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class HotbarScrollMixin {
    @Inject(method = "onScroll(JDD)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getInventory()Lnet/minecraft/world/entity/player/Inventory;"), cancellable = true)
    private void ec$noHotbarScroll(long handle, double xOffset, double yOffset, CallbackInfo ci) {
        if (ItemTweaks.INSTANCE.noHotbarScroll()) ci.cancel();
    }
}
