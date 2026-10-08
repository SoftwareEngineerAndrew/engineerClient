package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts "Blur In GUI"'s line at stratum 0 of every GUI state started while the frame's GUI is being
 * built (see {@code GuiBlurMixin} for the window and why a mid-frame reset needs it again).
 *
 * <p>A reset leaves the state with no marker, so marking right after one never trips the game's
 * "Can only blur once per frame".
 */
@Mixin(GuiRenderState.class)
public class GuiRenderStateBlurMixin {
    @Inject(method = "reset", at = @At("TAIL"))
    private void ec$blurBehindGui(CallbackInfo ci) {
        if (RandomStuff.INSTANCE.blursAfterReset()) ((GuiRenderState) (Object) this).blurBeforeThisStratum();
    }
}
