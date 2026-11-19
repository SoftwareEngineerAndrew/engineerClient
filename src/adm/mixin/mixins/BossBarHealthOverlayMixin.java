package com.adm.mixin.mixins;

import com.adm.events.RenderBossBarEvent;
import com.adm.features.impl.dungeon.BloodCamp;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BossHealthOverlay.class)
public abstract class BossBarHealthOverlayMixin {

    @Inject(method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V", at = @At("HEAD"), cancellable = true)
    private void onRenderBossBar(GuiGraphicsExtractor graphics, int x, int y, BossEvent event, CallbackInfo ci) {
        if (new RenderBossBarEvent(event).postAndCatch()) ci.cancel();
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/LerpingBossEvent;getName()Lnet/minecraft/network/chat/Component;"))
    private Component onBossBarName(LerpingBossEvent event, Operation<Component> original) {
        return BloodCamp.barName(event, original.call(event));
    }
}
