package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Random Stuff's Click GUI Size, text side: Odin's menu draws with the pixel font, and at a size that
 * isn't a whole number (1.5) each font pixel lands on 1 or 2 screen pixels, so letters come out
 * uneven. In the menu, text at such a scale is sampled bilinearly instead - even and slightly soft,
 * like a smooth font. Whole-number scales and everything outside the menu keep the crisp sampler.
 */
@Mixin(GlyphRenderState.class)
public abstract class ClickGuiTextMixin {
    @Shadow public abstract Matrix3x2fc pose();

    @ModifyArg(method = "textureSetup", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/SamplerCache;getClampToEdge(Lcom/mojang/renderpearl/api/textures/FilterMode;)Lcom/mojang/renderpearl/api/textures/GpuSampler;"))
    private FilterMode ec$smoothInClickGui(FilterMode mode) {
        Matrix3x2fc m = pose();
        return RandomStuff.INSTANCE.smoothsGuiText((float) Math.hypot(m.m00(), m.m01())) ? FilterMode.LINEAR : mode;
    }
}
