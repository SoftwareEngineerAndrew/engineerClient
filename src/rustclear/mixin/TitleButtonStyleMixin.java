package net.rustclear.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.TitleScreen;
import net.rustclear.mod.ui.ButtonStyle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On the title screen, buttons are drawn on Rustclear's slate panel instead of Minecraft's
 * button sprite. Only the background changes: each button's own label, icon, tooltip, loading
 * spinner and disabled state stay as Minecraft draws them. Every other screen is untouched.
 */
@Mixin(AbstractButton.class)
public class TitleButtonStyleMixin {
    @Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true)
    private void rustclear$titleStyle(GuiGraphicsExtractor g, CallbackInfo ci) {
        if (Minecraft.getInstance().gui.screen() instanceof TitleScreen) {
            ButtonStyle.drawFrame(g, (AbstractButton) (Object) this);
            ci.cancel();
        }
    }
}
