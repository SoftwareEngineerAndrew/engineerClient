package com.devgineerclient.mixin;

import com.devgineerclient.DevgineerClient;
import com.devgineerclient.misc.RenderTweaks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Render Tweaks' NVIDIA Persistent Buffers: GlDevice passes "disable" = true to BufferStorage.create on NVIDIA (and
 * Intel Gen7), which leaves the transient memory on its map-every-upload fallback. Passing false gives NVIDIA the
 * immutable, persistently mapped buffers every other GL 4.4+ driver gets.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlDevice")
public class RenderTweaksBufferStorageMixin {

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/BufferStorage;create(Lorg/lwjgl/opengl/GLCapabilities;Ljava/util/Set;Z)Lcom/mojang/renderpearl/backend/opengl/BufferStorage;"), index = 2, require = 0)
    private boolean dc$persistentBuffers(boolean disable) {
        if (!disable) return false;
        boolean on = false;
        try { on = RenderTweaks.INSTANCE.getPersistentBuffers(); } catch (Throwable ignored) {}
        if (on) DevgineerClient.INSTANCE.getLogger().info("[dc] Render Tweaks: persistent buffers on (NVIDIA)");
        return !on;
    }
}
