package com.engineerclient.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuBackend;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Video Settings' Exclusive Fullscreen work on Linux again. Up to 26.1 the game passed it to
 * GLFW as GLFW_SOFT_FULLSCREEN on every platform; 26.2 only acts on it on Windows, so on Linux
 * fullscreen is always the borderless kind. Under Xwayland a borderless fullscreen window can't
 * move a visible cursor, so opening a menu often leaves the cursor where it was instead of
 * centring it. The hint is read when the window is created, so a change applies after a restart.
 */
@Mixin(Window.class)
public abstract class ExclusiveFullscreenMixin {
    @Shadow @Final private boolean exclusiveFullscreen;

    @Unique private static boolean ec$exclusive;

    @Inject(method = "createWindow", at = @At("HEAD"))
    private void ec$rememberExclusive(GpuBackend backend, int width, int height, String title, long monitor, CallbackInfoReturnable<Long> cir) {
        ec$exclusive = exclusiveFullscreen;
    }

    @Inject(method = "createGlfwWindow", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private static void ec$softFullscreenHint(int width, int height, String title, long monitor, GpuBackend backend, CallbackInfoReturnable<Long> cir) {
        if (Util.getPlatform() != Util.OS.LINUX) return;
        GLFW.glfwWindowHint(GLFW.GLFW_SOFT_FULLSCREEN, ec$exclusive ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);
    }
}
