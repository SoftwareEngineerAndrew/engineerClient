package com.engineerclient.mixin.odin;

import com.engineerclient.hud.HudEditor;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.odtheking.odin.clickgui.settings.impl.HUDSetting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = HUDSetting.class, remap = false)
public class HudEditorCategoryMixin {
    @ModifyReturnValue(method = "isEnabled", at = @At("RETURN"), remap = false)
    private boolean ec$category(boolean original) {
        return original && !HudEditor.hides((HUDSetting) (Object) this);
    }
}
