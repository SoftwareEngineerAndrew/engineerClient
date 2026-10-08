package com.engineerclient.mixin.odin;

import com.engineerclient.misc.RandomStuff;
import com.engineerclient.splits.OdinSplitsLook;
import com.odtheking.odin.clickgui.ClickGUI;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Odin's menu opening. Random Stuff's Click GUI Size: Odin's own setting only goes in whole steps
 * (1, 2, 3, 4); this sets the scale it derives from it. And the Pace target boxes' grayed stand-ins
 * (your PBs) are brought up to date.
 */
@Mixin(value = ClickGUI.class, remap = false)
public class ClickGuiSizeMixin {
    @Shadow private static float scale;

    @Inject(method = "init", at = @At("RETURN"), remap = false)
    private void ec$size(CallbackInfo ci) {
        float size = RandomStuff.INSTANCE.clickGuiSize();
        if (size > 0f) scale = size / Minecraft.getInstance().getWindow().getGuiScale();
        OdinSplitsLook.refreshPlaceholders();
    }
}
