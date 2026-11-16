package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
public class ScreenTweaksTextShadowMixin {

    @ModifyReturnValue(method = "getShadowColor", at = @At("RETURN"))
    private int ec$noTextShadow(int color) {
        return Visibility.INSTANCE.noTextShadow() ? 0 : color;
    }
}
