package com.engineerclient.mixin;

import com.engineerclient.skyblock.TabTweaks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerTabOverlay.class)
public class TabTweaksMixin {
    @Inject(method = "extractPingIcon", at = @At("HEAD"), cancellable = true)
    private void ec$hidePing(GuiGraphicsExtractor graphics, int slotWidth, int xo, int yo, PlayerInfo info, CallbackInfo ci) {
        if (TabTweaks.INSTANCE.hidesPing()) ci.cancel();
    }

    @WrapOperation(
        method = "extractRenderState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/PlayerFaceExtractor;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/resources/Identifier;IIIZZI)V")
    )
    private void ec$hideHead(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int size, boolean hat, boolean flip, int color, Operation<Void> original) {
        if (TabTweaks.INSTANCE.hidesHead(texture)) return;
        original.call(graphics, texture, x, y, size, hat, flip, color);
    }
}
