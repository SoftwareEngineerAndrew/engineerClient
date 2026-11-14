package com.engineerclient.mixin;

import com.engineerclient.hud.HudEditor;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Gui.class)
public class HudEditorOpenMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen ec$hudEditor(Screen screen) {
        return HudEditor.replace(screen);
    }
}
