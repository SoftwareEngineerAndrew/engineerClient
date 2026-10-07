package com.engineerclient.mixin;

import com.engineerclient.misc.RandomStuff;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gives "Blur In GUI" its own strength, independent of vanilla's accessibility slider.
 *
 * <p>There is no radius argument anywhere in the blur path: {@code box_blur.fsh} reads
 * {@code MenuBlurRadius} out of the global settings uniform, and this one call in
 * {@code extractOptions} is where that number comes from. Without the redirect the feature would
 * inherit Menu Background Blur — and do nothing at all for anyone who has it at 0, or who picked a
 * graphics preset that set it there.
 *
 * <p>Only the frames EC is blurring are touched; every other frame gets the player's own setting,
 * so vanilla's menus keep blurring exactly as they were told to.
 */
@Mixin(GameRenderer.class)
public class BlurRadiusMixin {

    @Redirect(method = "extractOptions()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getMenuBackgroundBlurriness()I"))
    private int ec$blurRadius(Options options) {
        if (RandomStuff.INSTANCE.blursGui()) return RandomStuff.INSTANCE.blurRadius();
        return options.getMenuBackgroundBlurriness();
    }
}
