package com.coffeeclient.mixin;

import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * The FOV slider (Options, Video Settings and the pause menu's Options) goes to 130 instead of 110. The 110 is the
 * one in the FOV option's range (new IntRange(30, 110)), the only 110 in Options' constructor; the saved value
 * (options.txt, (fov - 70) / 40) has no limit of its own, and a saved 111-130 now loads instead of falling back to 70.
 */
@Mixin(Options.class)
public abstract class FovRangeMixin {
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 110), require = 0)
    private int cc$fovMax(int max) {
        return 130;
    }
}
