package net.rustclear.mod.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

/**
 * The duel teams' dimensions, rustclear:team_a and rustclear:team_b: void worlds like the Rustclear
 * overworld, so each team's copy of the dungeon is built at exactly Hypixel's coordinates (client
 * mods rely on the fixed dungeon grid; see DUNGEON_MIN_CHUNK in HypixelSignals) rather than at an
 * offset. They ship with the mod and are added to the Rustclear world as it is created (EngineWorld);
 * as a data pack they would depend on mod data packs being enabled for that fresh world.
 */
final class TeamDimensions {
    static final ResourceKey<Level> TEAM_A = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("rustclear", "team_a"));
    static final ResourceKey<Level> TEAM_B = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("rustclear", "team_b"));

    private TeamDimensions() {
    }

    /** The level a duel team races in. */
    static ResourceKey<Level> of(String team) {
        return "B".equals(team) ? TEAM_B : TEAM_A;
    }

    /** [dimensions] plus the two team dimensions, each a void overworld made by [generator]. */
    static WorldDimensions withTeams(HolderLookup.Provider provider, WorldDimensions dimensions, ChunkGenerator generator) {
        Holder<DimensionType> type = provider.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        Map<ResourceKey<LevelStem>, LevelStem> stems = new LinkedHashMap<>(dimensions.dimensions());
        stems.put(stem(TEAM_A), new LevelStem(type, generator));
        stems.put(stem(TEAM_B), new LevelStem(type, generator));
        return new WorldDimensions(stems);
    }

    private static ResourceKey<LevelStem> stem(ResourceKey<Level> level) {
        return ResourceKey.create(Registries.LEVEL_STEM, level.identifier());
    }
}
