package com.engineerclient.mixin;

import com.engineerclient.visual.Performance;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class PerformanceClickMixin {
    @Inject(method = "startAttack", at = @At("HEAD"))
    private void ec$leftClick(CallbackInfoReturnable<Boolean> cir) {
        Performance.addLeftClick();
    }

    @Inject(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;startUseItem()V", ordinal = 0))
    private void ec$rightClick(CallbackInfo ci) {
        Performance.addRightClick();
    }
}
