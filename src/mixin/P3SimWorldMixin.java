package com.engineerclient.mixin;

import com.engineerclient.p3sim.Arena;
import com.engineerclient.p3sim.SimServer;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * P3 Sim: the sim world's void generator builds the F7 boss arena into each new chunk. Only while
 * the sim's own server runs ({@link SimServer#getServer()}); every other flat world is untouched.
 */
@Mixin(FlatLevelSource.class)
public class P3SimWorldMixin {

    // 26.3 renamed fillFromNoise to buildTerrain (chunk first, plus the biome inputs).
    @Inject(method = "buildTerrain", at = @At("HEAD"))
    private void ec$arena(ChunkAccess chunk, Blender blender, RandomState random, StructureManager structures, BiomeManager biomes,
                          WorldGenRegion region, Set<Holder<Biome>> possibleBiomes, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (SimServer.INSTANCE.getServer() != null) Arena.INSTANCE.fill(chunk);
    }
}
