package com.engineerclient.mixin.odin;

import com.engineerclient.pov.ShareFps;
import com.odtheking.odin.clickgui.settings.Setting;
import com.odtheking.odin.clickgui.settings.impl.NumberSetting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * POV Previews' Sharing FPS at its lowest reads "none" on its slider (nothing is shared there): the text Odin shows comes
 * from NumberSetting.format, worked out whenever the value is set.
 */
@Mixin(value = NumberSetting.class, remap = false)
public class NumberSettingNoneMixin {
    @Inject(method = "format", at = @At("RETURN"), cancellable = true, remap = false)
    private void ec$none(Number value, CallbackInfoReturnable<String> cir) {
        if (ShareFps.showsNone(((Setting<?>) (Object) this).getName(), value)) cir.setReturnValue("none");
    }
}
