package com.engineerclient.mixin;

import com.engineerclient.skyblock.PingMenus;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class PingMenusClickMixin {
    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void ec$pmClick(int containerId, int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (PingMenus.onClick(containerId, slot, button, input)) ci.cancel();
    }

    @Inject(method = "handleContainerInput", at = @At("TAIL"))
    private void ec$pmAfterClick(int containerId, int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        PingMenus.afterClick();
    }
}
