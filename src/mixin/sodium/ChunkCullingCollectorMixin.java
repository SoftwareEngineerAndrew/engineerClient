package com.engineerclient.mixin.sodium;

import com.engineerclient.culling.ChunkCulling;
import com.engineerclient.culling.CullState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium putting a visible section (its section coordinates) on a render list, from its section tree or, with no tree to
 * go by, straight from the sections in view (FallbackVisibleChunkCollector hands each one here): a section Chunk Culling
 * ({@link ChunkCulling}) leaves out isn't listed, so nothing of it is drawn. It stays in Sodium's tree: still built,
 * updated and counted as visible for everything else.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.lists.VisibleChunkCollector", remap = false)
public class ChunkCullingCollectorMixin {

    @Inject(method = "visit(III)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ec$chunkCulling(int x, int y, int z, CallbackInfo ci) {
        if (CullState.culls(x, z)) ci.cancel();
    }
}
