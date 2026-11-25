package com.engineerclient.mixin;

import com.engineerclient.skyblock.PingMenus;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public class PingMenusCloseMixin {
    @Inject(method = "closeContainer", at = @At("HEAD"))
    private void ec$pmLocalClose(CallbackInfo ci) {
        PingMenus.onLocalClose();
    }
}
