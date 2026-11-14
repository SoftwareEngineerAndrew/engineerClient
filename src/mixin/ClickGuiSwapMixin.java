package com.engineerclient.mixin;

import com.engineerclient.gui.AdmClickGui;
import com.odtheking.odin.clickgui.ClickGUI;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Every way into Odin's Click GUI (its keybind, /od, Mod Menu) opens ADM's instead. */
@Mixin(Gui.class)
public class ClickGuiSwapMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen ec$admClickGui(Screen screen) {
        return screen instanceof ClickGUI ? AdmClickGui.INSTANCE : screen;
    }
}
