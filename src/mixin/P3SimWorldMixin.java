package com.engineerclient.mixin;

import com.engineerclient.p3sim.Arena;
import com.engineerclient.p3sim.SimServer;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * P3 Sim: the sim world's void generator builds the F7 boss arena into each new chunk. Only while
 * the sim's own server runs ({@link SimServer#getServer()}); every other flat world is untouched.
 */
@Mixin(FlatLevelSource.class)
public class P3SimWorldMixin {

    @Inject(method = "fillFromNoise", at = @At("HEAD"))
    private void ec$arena(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (SimServer.INSTANCE.getServer() != null) Arena.INSTANCE.fill(chunk);
    }
}
