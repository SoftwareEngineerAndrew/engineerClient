package com.engineerclient.mixin;

import com.engineerclient.render.ScreenTweaks;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
public class ScreenTweaksTextShadowMixin {

    @Inject(method = "getShadowColor", at = @At("HEAD"), cancellable = true)
    private void ec$noTextShadow(Style style, int color, CallbackInfoReturnable<Integer> cir) {
        if (ScreenTweaks.INSTANCE.noTextShadow()) cir.setReturnValue(0);
    }
}
