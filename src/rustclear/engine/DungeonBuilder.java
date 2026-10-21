package net.rustclear.mod.engine;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.LongStream;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * Puts a planned dungeon's blocks into a level (Smalldungeons main.rs populate_dungeon_world,
 * Room::load_into_world and Door::load_into_world), replacing the last one.
 *
 * Rather than a Level.setBlock per block (each one updating heightmaps, light, block entities and
 * the client), a build is staged in memory first, one 16x16x16 array per section, then written
 * straight into the chunk sections, only where the world differs. Every section either dungeon
 * touches ends up holding exactly the new dungeon, air everywhere else, so the old dungeon (and
 * anything a run changed) is cleared in the same pass. Heightmaps and sky light sources are
 * worked out once per chunk, and light is rechecked only where a block's light properties
 * changed, as Level.setBlock would. Nothing is sent to clients block by block: the caller resends
 * [touchedChunks] whole (HypixelSignals.newDungeon).
 */
public final class DungeonBuilder {
    private static final int LEGACY_BEDROCK = 7 << 4;
    private static final int LEGACY_STONE = 1 << 4;
    private static final int LEGACY_GOLD_BLOCK = 41 << 4;
    private static final int LEGACY_TORCH_UP = 50 << 4 | 5;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int SECTION_VOLUME = 16 * 16 * 16;
    /** The block-state palette strategy, as PalettedContainerFactory makes it for levels. */
    /** RUSTCLEAR_CHECK_SECTIONS (development): read every new section back against what it should hold. */
    private static final boolean CHECK_SECTIONS = System.getenv("RUSTCLEAR_CHECK_SECTIONS") != null;
    private static final Strategy<BlockState> BLOCK_STRATEGY = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
    /**
     * The dungeon's footprint ends here (x and z): the 6x6 grid and its doors stay at or below it,
     * and Hypixel's boss arena (BossArena) starts at -8. A build clears nothing east or south of it.
     */
    static final int FOOTPRINT_MAX = Layout.ORIGIN_X + Layout.GRID * Layout.CELL - 1;
    /** The footprint's chunks as a square round its middle (x and z alike): -13..-1 is 13 wide. */
    static final int FOOTPRINT_CENTER_CHUNK = ((Layout.ORIGIN_X >> 4) + (FOOTPRINT_MAX >> 4)) / 2;
    static final int FOOTPRINT_CHUNK_RADIUS = ((FOOTPRINT_MAX >> 4) - (Layout.ORIGIN_X >> 4) + 1) / 2;

    private final ServerLevel level;
    private final LegacyBlocks legacy;

    /** The dungeon being built: SectionPos key to that section's blocks (null: not part of it). */
    private final Long2ObjectOpenHashMap<BlockState[]> staged = new Long2ObjectOpenHashMap<>();
    /** Emptied section arrays, reused by the next build. */
    private final ArrayDeque<BlockState[]> spare = new ArrayDeque<>();
    private long lastKey = Long.MIN_VALUE;
    private BlockState[] lastSection;
    /** Positions that may need their shape worked out from neighbours, in the order they were staged. */
    private final LongArrayList settleCandidates = new LongArrayList();
    private final Reference2BooleanOpenHashMap<Block> settles = new Reference2BooleanOpenHashMap<>();

    /** The sections the dungeon in the world now covers. */
    private LongSet built = new LongOpenHashSet();
    private final LongOpenHashSet touchedChunks = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<LevelChunk> chunks = new Long2ObjectOpenHashMap<>();
    private final LongArrayList lightChecks = new LongArrayList();
    private final LongArrayList blockEntities = new LongArrayList();
    private int stagedCount;
    private int written;

    public DungeonBuilder(ServerLevel level) {
        this.level = level;
        this.legacy = LegacyBlocks.shared();
    }

    /** Converts every block id the rooms use up front (the first build otherwise spends seconds in the data fixer). */
    public void warm(RoomStore store) {
        legacy.warm(store);
    }

    /** Builds the whole dungeon. Returns where players start (the entrance room's spawn spot) and which way they face. */
    public Spawn build(DungeonPlan plan, RoomStore store, Random random, BuildTimer timer) {
        stagedCount = 0;
        for (PlacedRoom room : plan.rooms()) {
            placeRoom(room);
        }
        for (PlacedRoom room : plan.rooms()) {
            roomFixes(room);
        }
        for (Layout.Door door : plan.layout().doors) {
            placeDoor(door, store.doorPatterns, random);
        }
        timer.lap("stage");
        commit();
        timer.lap("commit");
        settleShapes();
        timer.lap("settle");
        finish();
        timer.lap("finish");

        for (PlacedRoom room : plan.rooms()) {
            if (room.slot().type() == RoomType.ENTRANCE) {
                BlockPos pos = room.toWorld(15, 72, 18);
                return new Spawn(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, room.facing().rotateYaw(180f));
            }
        }
        return new Spawn(15.5, 75, 15.5, 0);
    }

    public record Spawn(double x, double y, double z, float yaw) {}

    /** Blocks the last [build] staged, and how many of those (plus cleared ones) it actually changed in the world. */
    public String stats() {
        return stagedCount + " staged, " + written + " written";
    }

    /** The chunks (ChunkPos longs) the last [build] changed, old dungeon included: these need resending. */
    public LongSet touchedChunks() {
        return touchedChunks;
    }

    // ---------------------------------------------------------------- staging

    private void stage(int x, int y, int z, BlockState state) {
        if (level.isOutsideBuildHeight(y)) {
            return;
        }
        long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        BlockState[] section = lastSection;
        if (key != lastKey) {
            section = staged.get(key);
            if (section == null) {
                section = spare.isEmpty() ? new BlockState[SECTION_VOLUME] : spare.pop();
                staged.put(key, section);
            }
            lastKey = key;
            lastSection = section;
        }
        section[index(x & 15, y & 15, z & 15)] = state;
        stagedCount++;
        if (settles(state)) {
            settleCandidates.add(BlockPos.asLong(x, y, z));
        }
    }

    private static int index(int x, int y, int z) {
        return y << 8 | z << 4 | x;
    }

    private boolean settles(BlockState state) {
        Block block = state.getBlock();
        if (settles.containsKey(block)) {
            return settles.getBoolean(block);
        }
        boolean settles = block instanceof DoublePlantBlock || block instanceof CrossCollisionBlock || block instanceof WallBlock
            || block instanceof StairBlock || block instanceof ChestBlock || block instanceof DoorBlock
            || block instanceof RedstoneWireBlock || block instanceof TripWireBlock;
        this.settles.put(block, settles);
        return settles;
    }

    private void stageLegacy(int x, int y, int z, int legacyId, Facing facing) {
        stage(x, y, z, legacy.get(legacyId, facing));
    }

    private void stage(BlockPos pos, BlockState state) {
        stage(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    private void fill(int legacyId, int x1, int y1, int z1, int x2, int y2, int z2) {
        BlockState state = legacy.get(legacyId);
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    stage(x, y, z, state);
                }
            }
        }
    }

    // ---------------------------------------------------------------- writing to the world

    /** Writes every staged section (and empties every section only the old dungeon had) where the world differs. */
    private void commit() {
        written = 0;
        touchedChunks.clear();
        chunks.clear();
        lightChecks.clear();
        blockEntities.clear();
        LevelLightEngine light = level.getChunkSource().getLightEngine();
        LongOpenHashSet sections = new LongOpenHashSet(built);
        sections.addAll(staged.keySet());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        SectionWriter w = writer;
        for (LongIterator it = sections.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            LevelChunk chunk = chunk(sx, sz);
            int index = level.getSectionIndexFromSectionY(sy);
            LevelChunkSection section = chunk.getSection(index);
            BlockState[] target = staged.get(key);
            boolean wasEmpty = section.hasOnlyAir();
            if (target == null && wasEmpty) {
                continue;
            }
            touchedChunks.add(ChunkPos.pack(sx, sz));
            int changed = w.diff(section.getStates(), target, sx, sy, sz);
            for (int i = 0; i < w.removed.size(); i++) {
                // Even an unchanged chest or head starts the new dungeon fresh, as it did when the
                // old one was cleared to air first.
                chunk.removeBlockEntity(pos.set(w.removed.getLong(i)));
            }
            blockEntities.addAll(w.added);
            lightChecks.addAll(w.light);
            if (changed > 0) {
                section = new LevelChunkSection(w.pack(), section.getBiomes());
                if (CHECK_SECTIONS) {
                    w.check(section);
                }
                chunk.getSections()[index] = section;
                written += changed;
            }
            boolean nowEmpty = section.hasOnlyAir();
            if (wasEmpty != nowEmpty) {
                light.updateSectionStatus(SectionPos.of(sx, sy, sz), nowEmpty);
                level.getChunkSource().onSectionEmptinessChanged(sx, sy, sz, nowEmpty);
            }
        }
        // The staged arrays go back for the next build; what's in the world is now [built].
        built = new LongOpenHashSet(staged.keySet());
        for (Long2ObjectMap.Entry<BlockState[]> entry : staged.long2ObjectEntrySet()) {
            Arrays.fill(entry.getValue(), null);
            spare.push(entry.getValue());
        }
        staged.clear();
        lastKey = Long.MIN_VALUE;
        lastSection = null;
    }

    private final SectionWriter writer = new SectionWriter();

    /**
     * [commit]'s work on one section, apart from the level so EngineWorld's preload can run it
     * ([warmWriter]): the per-block diff against what the section holds, then the new contents packed
     * into a palette of the right size.
     *
     * Two first-build costs it removes (dev client, timing scenario): writing block by block grew
     * each section's palette a step at a time (4, 5, 6, 7, 8 bits, then global), re-encoding all
     * 4096 entries each step; and the JIT compiled the diff on the first build, where every old
     * block is air, so the first /rs (old chests, heads, full palettes) deoptimised it: its pass
     * took ~330 ms against ~55 ms once recompiled.
     */
    static final class SectionWriter {
        final BlockState[] finalStates = new BlockState[SECTION_VOLUME];
        final LongArrayList removed = new LongArrayList();
        final LongArrayList added = new LongArrayList();
        final LongArrayList light = new LongArrayList();
        private final Reference2IntOpenHashMap<BlockState> paletteIndex = new Reference2IntOpenHashMap<>();
        private final List<BlockState> palette = new ArrayList<>();
        private final int[] indices = new int[SECTION_VOLUME];

        /**
         * Fills [finalStates] from [target] (null entries air; a null array all air), keeping the
         * boss arena's blocks in sections both use; lists block entities to remove and add and
         * light checks. Returns how many blocks change.
         */
        int diff(PalettedContainer<BlockState> old, BlockState[] target, int sx, int sy, int sz) {
            removed.clear();
            added.clear();
            light.clear();
            boolean shared = (sx << 4) + 15 > FOOTPRINT_MAX || (sz << 4) + 15 > FOOTPRINT_MAX;
            int changed = 0;
            for (int i = 0; i < SECTION_VOLUME; i++) {
                int lx = i & 15;
                int lz = i >> 4 & 15;
                int ly = i >> 8;
                BlockState was = old.get(lx, ly, lz);
                BlockState next = target == null ? null : target[i];
                if (next == null) {
                    if (shared && ((sx << 4 | lx) > FOOTPRINT_MAX || (sz << 4 | lz) > FOOTPRINT_MAX)) {
                        // The boss arena's half of a section both use: not the dungeon's to clear.
                        finalStates[i] = was;
                        continue;
                    }
                    next = AIR;
                }
                finalStates[i] = next;
                if (was == next && !next.hasBlockEntity()) {
                    continue;
                }
                long at = BlockPos.asLong(sx << 4 | lx, sy << 4 | ly, sz << 4 | lz);
                if (was.hasBlockEntity()) {
                    removed.add(at);
                }
                if (next.hasBlockEntity()) {
                    added.add(at);
                }
                if (was != next) {
                    changed++;
                    if (LightEngine.hasDifferentLightProperties(was, next)) {
                        light.add(at);
                    }
                }
            }
            return changed;
        }

        /** [finalStates] as a block-state container whose palette is the right size from the start. */
        PalettedContainer<BlockState> pack() {
            paletteIndex.clear();
            palette.clear();
            for (int i = 0; i < SECTION_VOLUME; i++) {
                BlockState st = finalStates[i];
                int id = paletteIndex.getOrDefault(st, -1);
                if (id < 0) {
                    id = palette.size();
                    paletteIndex.put(st, id);
                    palette.add(st);
                }
                indices[i] = id;
            }
            // Strategy's block-state configurations (bits in storage by distinct values): one value
            // 0, linear 4 up to 16 values, hashmap 5-8, then global, stored at the same count.
            int need = net.minecraft.util.Mth.ceillog2(palette.size());
            int bits = need == 0 ? 0 : Math.max(4, need);
            Optional<LongStream> storage = bits == 0 ? Optional.empty()
                : Optional.of(LongStream.of(new SimpleBitStorage(bits, SECTION_VOLUME, indices).getRaw()));
            return PalettedContainer.unpack(BLOCK_STRATEGY, new PalettedContainerRO.PackedData<>(List.copyOf(palette), storage))
                .getOrThrow();
        }

        /** RUSTCLEAR_CHECK_SECTIONS: [section] must hold exactly [finalStates]. */
        void check(LevelChunkSection section) {
            for (int i = 0; i < SECTION_VOLUME; i++) {
                BlockState got = section.getBlockState(i & 15, i >> 8, i >> 4 & 15);
                if (got != finalStates[i]) {
                    throw new IllegalStateException("Packed section block " + i + " came out " + got + ", not " + finalStates[i]
                        + " (" + palette.size() + " palette entries)");
                }
            }
        }
    }

    /**
     * Runs [SectionWriter] over sections built from the rooms' own blocks (EngineWorld's preload,
     * before the first build): first over all-air sections as the first build sees them, then
     * over packed rooms with chests and heads, overwritten by other rooms and cleared, as /rs
     * sees them, so the JIT compiles the diff with every branch it will meet.
     */
    static void warmWriter(RoomStore store) {
        LegacyBlocks legacy = LegacyBlocks.shared();
        SectionWriter w = new SectionWriter();
        List<BlockState[]> samples = new ArrayList<>();
        for (RoomData room : store.rooms) {
            short[] blocks = room.blocks();
            for (int start = 0; start + SECTION_VOLUME <= blocks.length && samples.size() < 100; start += SECTION_VOLUME * 3) {
                BlockState[] a = new BlockState[SECTION_VOLUME];
                for (int i = 0; i < SECTION_VOLUME; i++) {
                    int id = blocks[start + i] & 0xFFFF;
                    a[i] = id == 0 && (i & 7) == 0 ? null : legacy.get(id);
                }
                samples.add(a);
            }
        }
        if (samples.isEmpty()) {
            return;
        }
        PalettedContainer<BlockState> air = new PalettedContainer<>(AIR, BLOCK_STRATEGY);
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < samples.size(); k++) {
                BlockState[] target = samples.get(k);
                int sx = k % 3 == 0 ? (FOOTPRINT_MAX >> 4) : -5;
                w.diff(air, target, sx, 4, -5);
                PalettedContainer<BlockState> full = w.pack();
                w.diff(full, samples.get((k + 1) % samples.size()), sx, 4, -5);
                w.diff(w.pack(), null, sx, 4, -5);
            }
        }
    }

    private LevelChunk chunk(int cx, int cz) {
        return chunks.computeIfAbsent(ChunkPos.pack(cx, cz), k -> level.getChunk(cx, cz));
    }

    /** One block straight into its section, for [settleShapes]; light as in [commit]. */
    private void write(BlockPos pos, BlockState state) {
        LevelChunk chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
        LevelChunkSection section = chunk.getSection(level.getSectionIndex(pos.getY()));
        BlockState old = section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state, false);
        if (LightEngine.hasDifferentLightProperties(old, state)) {
            lightChecks.add(pos.asLong());
        }
    }

    /** Block entities, heightmaps, sky light sources and light checks, once the blocks are all in. */
    private void finish() {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < blockEntities.size(); i++) {
            pos.set(blockEntities.getLong(i));
            LevelChunk chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk.getBlockState(pos).hasBlockEntity()) {
                chunk.getBlockEntity(pos.immutable(), LevelChunk.EntityCreationType.IMMEDIATE);
            }
        }
        for (LongIterator it = touchedChunks.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            LevelChunk chunk = chunk(ChunkPos.getX(key), ChunkPos.getZ(key));
            EnumSet<Heightmap.Types> types = EnumSet.noneOf(Heightmap.Types.class);
            for (Map.Entry<Heightmap.Types, Heightmap> heightmap : chunk.getHeightmaps()) {
                types.add(heightmap.getKey());
            }
            Heightmap.primeHeightmaps(chunk, types);
            chunk.initializeLightSources();
            chunk.markUnsaved();
        }
        LightBatch.check(level.getChunkSource().getLightEngine(), lightChecks);
        chunks.clear();
    }

    // ---------------------------------------------------------------- what goes in

    private void placeRoom(PlacedRoom room) {
        RoomData data = room.data();
        if (data.blocks().length == 0) {
            // Room::load_default: a stone floor per cell for a room with no recorded blocks.
            for (Layout.Segment s : room.slot().segments()) {
                int x = s.x() * Layout.CELL + Layout.ORIGIN_X;
                int z = s.z() * Layout.CELL + Layout.ORIGIN_Z;
                fill(LEGACY_STONE, x, data.bottom(), z, x + 30, data.bottom(), z + 30);
            }
            return;
        }
        int width = data.width();
        int length = data.length();
        short[] blocks = data.blocks();
        Facing facing = room.facing();
        int i = 0;
        for (int y = data.bottom(); i < blocks.length; y++) {
            for (int z = 0; z < length && i < blocks.length; z++) {
                for (int x = 0; x < width && i < blocks.length; x++, i++) {
                    int id = blocks[i] & 0xFFFF;
                    if (id != 0) {
                        stage(room.cornerX() + facing.rotateX(x, z), y, room.cornerZ() + facing.rotateZ(x, z),
                            legacy.get(id, facing));
                    }
                }
            }
        }
    }

    /** The per-room block tweaks the server makes after placing (load_into_world, populate_dungeon_world). */
    private void roomFixes(PlacedRoom room) {
        switch (room.data().name()) {
            case "Gold" -> stage(room.toWorld(15, 100, 15), legacy.get(LEGACY_GOLD_BLOCK));
            // The server places these three without turning them with the room, so they only
            // land right in rooms facing north; turned, they fix the room whichever way it faces
            // (and Odin then recognises its centre column).
            case "Rails" -> {
                for (int y = 54; y <= 58; y++) {
                    stage(room.toWorld(15, y, 15), AIR);
                }
            }
            case "Flags" -> stage(room.toWorld(55, 88, 47), AIR);
            case "Grand Library" -> stage(room.toWorld(47, 86, 15), legacy.get(LEGACY_TORCH_UP));
            // Where the room data differs from Hypixel's room under its centre (what Odin
            // recognises rooms by): a second bedrock layer, and bark-sided oak.
            case "Sand Dragon" -> stage(room.toWorld(15, 65, 15), Blocks.BEDROCK.defaultBlockState());
            case "End" -> stage(room.toWorld(15, 80, 15), Blocks.OAK_WOOD.defaultBlockState());
            default -> {
            }
        }
    }

    /** Door::load_into_world. */
    private void placeDoor(Layout.Door door, List<short[]> patterns, Random random) {
        int dx = door.alongX() ? 3 : 2;
        int dz = door.alongX() ? 2 : 3;
        int x = door.x();
        int z = door.z();
        fill(LEGACY_BEDROCK, x - dx, 67, z - dz, x + dx, 66, z + dz);
        fill(LEGACY_STONE, x - (dz - 2) * 2, 68, z - (dx - 2) * 2, x + (dz - 2) * 2, 68, z + (dx - 2) * 2);
        fill(0, x - dx, 69, z - dz, x + dx, 73, z + dz);

        // Wither doors are normal doors with coal in the middle (doors.txt: 0 blood, 1 entrance, 1-7 normal).
        short[] pattern = switch (door.type()) {
            case BLOOD -> patterns.get(0);
            case ENTRANCE -> patterns.get(1);
            case WITHER, NORMAL -> patterns.get(1 + random.nextInt(7));
        };
        Facing facing = door.alongX() ? Facing.EAST : Facing.NORTH;
        for (int i = 0; i < pattern.length; i++) {
            int px = i % 5 - 2;
            int pz = (i / 5) % 5 - 2;
            int py = i / 25;
            stageLegacy(x + facing.rotateX(px, pz), 69 + py, z + facing.rotateZ(px, pz), pattern[i] & 0xFFFF, facing);
        }

        int core = switch (door.type()) {
            case NORMAL -> 0;
            case ENTRANCE -> 97 << 4 | 5; // infested chiseled stone bricks
            case WITHER -> 173 << 4; // coal block
            case BLOOD -> 159 << 4 | 14; // red terracotta
        };
        fill(core, x - 1, 69, z - 1, x + 1, 72, z + 1);
    }

    /**
     * 1.8 worked out fence, pane, wall and stair connections, double chests and the top halves of
     * doors and tall plants from their neighbours; today they are stored in the block. Works them
     * out once everything is in, for the blocks of those kinds, in the order they were placed.
     */
    private void settleShapes() {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < settleCandidates.size(); i++) {
            pos.set(settleCandidates.getLong(i));
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            BlockState settled = state;
            if (block instanceof DoublePlantBlock && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
                BlockState below = level.getBlockState(pos.below());
                if (below.getBlock() instanceof DoublePlantBlock) {
                    settled = below.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
                }
            } else if (block instanceof CrossCollisionBlock || block instanceof WallBlock || block instanceof StairBlock
                || block instanceof ChestBlock || block instanceof DoorBlock || block instanceof RedstoneWireBlock
                || block instanceof TripWireBlock) {
                settled = Block.updateFromNeighbourShapes(state, level, pos);
            }
            if (settled != state && !settled.isAir()) {
                write(pos, settled);
            }
        }
        settleCandidates.clear();
    }
}
