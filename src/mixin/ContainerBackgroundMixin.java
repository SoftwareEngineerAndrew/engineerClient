package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class ContainerBackgroundMixin {
    @Inject(method = "extractTransparentBackground", at = @At("HEAD"), cancellable = true)
    private void ec$noTint(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if ((Object) this instanceof AbstractContainerScreen<?> && Visibility.hidesContainerBackground()) ci.cancel();
    }
}
