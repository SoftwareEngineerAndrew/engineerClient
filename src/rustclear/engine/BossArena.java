package net.rustclear.mod.engine;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.rustclear.mod.RustclearMod;

/**
 * Hypixel's boss arena (OVERHAUL_PLAN.md decision 4): Rustclear's own "Bossroom" (rooms.bin.gz
 * room 40, 142 x 155 x 254 from y 0) at its origin (-8, -8), next to the dungeon as on Hypixel.
 * It is built once per level and world session, never on /rs: a run starts it when the blood door
 * opens and it goes in a slice of sections each tick during the camp (about 1,400 sections, 5.6M
 * blocks), so no tick stalls. The dungeon's builder never clears east or south of its footprint
 * (DungeonBuilder.FOOTPRINT_MAX), and what a boss fight changes is put back by BossBlocks.
 * Checked against the knowledge base's positions (PROGRESS.md Phase 4): the entry floor, the
 * beacon at (73, 221, 73), both pylon plates, the crystal pads, Storm's and Necron's spots and
 * the end teleport's floor are all where Hypixel has them.
 */
final class BossArena {
    static final int ORIGIN_X = -8;
    static final int ORIGIN_Z = -8;
    /** Sections written per tick while it builds (about 5 ms; the whole arena in about 11 s of the camp). */
    private static final int SECTIONS_PER_TICK = 8;

    private static final Map<ServerLevel, BossArena> ARENAS = new WeakHashMap<>();

    private final ServerLevel level;
    private final RoomData room;
    private final LegacyBlocks legacy = LegacyBlocks.shared();
    /** Section keys still to write, in order (bottom up). */
    private final LongArrayList todo = new LongArrayList();
    private int next;
    private boolean started;
    private boolean done;
    private final LongOpenHashSet touched = new LongOpenHashSet();
    private final LongArrayList lightChecks = new LongArrayList();
    private final LongArrayList blockEntities = new LongArrayList();
    private final LongArrayList settle = new LongArrayList();
    private long nanos;
    private int written;

    static synchronized BossArena of(ServerLevel level) {
        return ARENAS.computeIfAbsent(level, BossArena::new);
    }

    private BossArena(ServerLevel level) {
        this.level = level;
        RoomData found = null;
        for (RoomData r : RoomStore.get().rooms) {
            if (r.type() == RoomType.BOSS || "Bossroom".equals(r.name())) {
                found = r;
            }
        }
        this.room = found;
    }

    boolean ready() {
        return done;
    }

    /** Starts building (if it isn't already built or under way). */
    void start() {
        if (started || room == null) {
            return;
        }
        started = true;
        int x1 = ORIGIN_X >> 4;
        int z1 = ORIGIN_Z >> 4;
        int x2 = (ORIGIN_X + room.width() - 1) >> 4;
        int z2 = (ORIGIN_Z + room.length() - 1) >> 4;
        int y1 = room.bottom() >> 4;
        int y2 = (room.bottom() + room.height() - 1) >> 4;
        for (int sy = y1; sy <= y2; sy++) {
            for (int sz = z1; sz <= z2; sz++) {
                for (int sx = x1; sx <= x2; sx++) {
                    todo.add(SectionPos.asLong(sx, sy, sz));
                }
            }
        }
    }

    /** Every tick: the next slice. */
    void tick() {
        if (!started || done) {
            return;
        }
        step(SECTIONS_PER_TICK);
    }

    /** All of what's left at once (a run that reached the boss before the arena was done). */
    void finishNow() {
        start();
        if (!done) {
            step(Integer.MAX_VALUE);
        }
    }

    private void step(int sections) {
        long t0 = System.nanoTime();
        LevelLightEngine light = level.getChunkSource().getLightEngine();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int n = 0; n < sections && next < todo.size(); n++, next++) {
            long key = todo.getLong(next);
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            if (level.isOutsideBuildHeight(sy << 4)) {
                continue;
            }
            LevelChunk chunk = level.getChunk(sx, sz);
            LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sy));
            boolean wasEmpty = section.hasOnlyAir();
            boolean changed = false;
            for (int ly = 0; ly < 16; ly++) {
                int y = sy << 4 | ly;
                int ry = y - room.bottom();
                if (ry < 0 || ry >= room.height()) {
                    continue;
                }
                for (int lz = 0; lz < 16; lz++) {
                    int z = sz << 4 | lz;
                    int rz = z - ORIGIN_Z;
                    if (rz < 0 || rz >= room.length()) {
                        continue;
                    }
                    for (int lx = 0; lx < 16; lx++) {
                        int x = sx << 4 | lx;
                        int rx = x - ORIGIN_X;
                        if (rx < 0 || rx >= room.width()) {
                            continue;
                        }
                        int id = room.blockAt(rx, y, rz);
                        BlockState state = id == 0 ? Blocks.AIR.defaultBlockState() : legacy.get(id, Facing.NORTH);
                        BlockState old = section.getBlockState(lx, ly, lz);
                        if (old == state) {
                            continue;
                        }
                        pos.set(x, y, z);
                        if (old.hasBlockEntity()) {
                            chunk.removeBlockEntity(pos);
                        }
                        section.setBlockState(lx, ly, lz, state, false);
                        written++;
                        changed = true;
                        if (state.hasBlockEntity()) {
                            blockEntities.add(pos.asLong());
                        }
                        if (LightEngine.hasDifferentLightProperties(old, state)) {
                            lightChecks.add(pos.asLong());
                        }
                        Block b = state.getBlock();
                        if (b instanceof CrossCollisionBlock || b instanceof WallBlock || b instanceof StairBlock) {
                            settle.add(pos.asLong());
                        }
                    }
                }
            }
            if (changed) {
                touched.add(ChunkPos.pack(sx, sz));
            }
            boolean nowEmpty = section.hasOnlyAir();
            if (wasEmpty != nowEmpty) {
                light.updateSectionStatus(SectionPos.of(sx, sy, sz), nowEmpty);
                level.getChunkSource().onSectionEmptinessChanged(sx, sy, sz, nowEmpty);
            }
        }
        nanos += System.nanoTime() - t0;
        if (next >= todo.size()) {
            finish();
        }
    }

    private void finish() {
        long t0 = System.nanoTime();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < settle.size(); i++) {
            pos.set(settle.getLong(i));
            BlockState state = level.getBlockState(pos);
            BlockState settled = Block.updateFromNeighbourShapes(state, level, pos);
            if (settled != state && !settled.isAir()) {
                LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                chunk.getSection(level.getSectionIndex(pos.getY()))
                    .setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, settled, false);
            }
        }
        for (int i = 0; i < blockEntities.size(); i++) {
            pos.set(blockEntities.getLong(i));
            LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk.getBlockState(pos).hasBlockEntity()) {
                chunk.getBlockEntity(pos.immutable(), LevelChunk.EntityCreationType.IMMEDIATE);
            }
        }
        for (LongIterator it = touched.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            LevelChunk chunk = level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
            EnumSet<Heightmap.Types> types = EnumSet.noneOf(Heightmap.Types.class);
            for (Map.Entry<Heightmap.Types, Heightmap> heightmap : chunk.getHeightmaps()) {
                types.add(heightmap.getKey());
            }
            Heightmap.primeHeightmaps(chunk, types);
            chunk.initializeLightSources();
            chunk.markUnsaved();
        }
        LightBatch.check(level.getChunkSource().getLightEngine(), lightChecks);
        resend();
        nanos += System.nanoTime() - t0;
        done = true;
        RustclearMod.LOGGER.info("Built the boss arena in {} ms of server time over {} sections ({} blocks written, {} chunks)",
            nanos / 1_000_000, todo.size(), written, touched.size());
        settle.clear();
        blockEntities.clear();
        lightChecks.clear();
    }

    /** Players whose clients already hold an arena chunk get it again, whole. */
    private void resend() {
        for (ServerPlayer player : level.players()) {
            for (LongIterator it = touched.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                ChunkPos cp = new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key));
                if (!level.getChunkSource().chunkMap.getPlayers(cp, false).contains(player)) {
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x(), cp.z());
                if (chunk != null) {
                    player.connection.send(new ClientboundForgetLevelChunkPacket(cp));
                    player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
                }
            }
        }
    }
}
