package com.engineerclient.mixin.sodium;

import com.engineerclient.culling.ChunkCulling;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium setting a frame's terrain up (SodiumWorldRenderer.setupTerrain's first call into its section manager, with the
 * camera's position): Chunk Culling ({@link ChunkCulling}) works out which chunk columns it keeps. When they changed, the
 * render lists are made again this frame (finalizeRenderLists, later in the same setup, reads the flag), as Sodium does
 * itself when the camera moved.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public class ChunkCullingFrameMixin {
    @Shadow(remap = false) private boolean needsRenderListUpdate;

    @Inject(method = "prepareFrame", at = @At("HEAD"), remap = false, require = 0)
    private void ec$chunkCulling(Vector3dc cameraPosition, CallbackInfo ci) {
        if (ChunkCulling.frame(cameraPosition.x(), cameraPosition.y(), cameraPosition.z())) this.needsRenderListUpdate = true;
    }
}
