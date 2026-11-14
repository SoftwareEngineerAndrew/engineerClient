package com.engineerclient.mixin;

import com.engineerclient.render.BossBar;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BossHealthOverlay.class)
public class BossBarTextMixin {
    @Inject(method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V", at = @At("TAIL"))
    private void ec$bossBarText(GuiGraphicsExtractor graphics, int x, int y, BossEvent event, CallbackInfo ci) {
        BossBar.draw(graphics, x, y, event);
    }
}
