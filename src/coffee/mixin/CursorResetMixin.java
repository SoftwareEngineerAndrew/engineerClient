package com.coffeeclient.mixin;

import com.coffeeclient.misc.CursorReset;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Random Stuff's Force Cursor Reset: {@link CursorReset#released} after a captured cursor is let go. */
@Mixin(MouseHandler.class)
public abstract class CursorResetMixin {
    @Shadow private boolean mouseGrabbed;
    @Unique private boolean cc$wasGrabbed;

    @Inject(method = "releaseMouse", at = @At("HEAD"))
    private void cc$beforeRelease(CallbackInfo ci) {
        cc$wasGrabbed = mouseGrabbed;
    }

    @Inject(method = "releaseMouse", at = @At("TAIL"))
    private void cc$afterRelease(CallbackInfo ci) {
        if (cc$wasGrabbed) CursorReset.released();
    }
}
