package com.engineerclient.mixin;

import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets {@code GuiBlurMixin} see whether this frame's one blur is already spent. */
@Mixin(GuiRenderState.class)
public interface GuiRenderStateAccessor {
    @Accessor("firstStratumAfterBlur")
    int ec$firstStratumAfterBlur();
}
