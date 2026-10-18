package com.engineerclient.p3sim.run

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.ChunkAccess
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A rectangle of the ADS world with a block source of its own. The world is built from regions (BRIEF "World frame"):
 *  - the DUNGEON region, x,z in `[-200, -10]` (`DungeonFrame.ORIGIN..DUNGEON_MAX`), provided by the layout module per run;
 *  - the ARENA region, the boss arena at x,z >= `DungeonFrame.ARENA_MIN` (-8), provided by the engine. Its extension is
 *    CLIPPED to that rectangle while a dungeon is loaded: the old `arena-ext.bin` writes 2,156 blocks into dungeon tile
 *    (5,5)'s corner (x,z -16..-10), which would clobber a real room.
 * Chunk (-1,-1) (x,z -16..-1) is shared: the dungeon owns its columns up to -10, the arena from -8; the two never write the
 * same cell, so the order of [WorldRegions.fillAll] does not matter.
 *
 * A region never reads another region's blocks. Each one resyncs on its own: the arena compares only its own box, a dungeon
 * restores its rooms from its own tables (doors and puzzles through their modules), never through the arena table.
 */
interface WorldRegion {
    /** For logs and messages ("dungeon", "arena"). */
    val name: String

    /** The inclusive block rectangle this region may write. */
    val minX: Int
    val maxX: Int
    val minZ: Int
    val maxZ: Int

    /**
     * Writes this region's part of a freshly generated chunk, straight into its sections with no updates (so Odin's chunk
     * scan sees the finished blocks). Heightmaps are primed once by [WorldRegions.fillAll] after every region has filled, so do not prime them here. Called from the generator on WORKER threads: no world access, no shared mutable state
     * beyond what is safe to read concurrently. Must not write outside the rectangle.
     */
    fun fill(chunk: ChunkAccess)

    /**
     * Compares the live world with this region's start state and puts differences back (clients told, no neighbour updates).
     * Server thread. Returns how many blocks it fixed. A savepoint restore calls this for every region.
     */
    fun resync(level: ServerLevel): Int

    fun intersects(chunkMinX: Int, chunkMinZ: Int): Boolean =
        chunkMinX + 15 >= minX && chunkMinX <= maxX && chunkMinZ + 15 >= minZ && chunkMinZ <= maxZ
}

/**
 * The regions of the world. The generator hook calls [fillAll]; the engine registers them.
 *
 * Lifecycle (the order matters because the world is built fresh on every open and the next world's regions are registered BEFORE the
 * previous world has finished stopping): a region that has filled a chunk is "used" by the world that is running. When that world's
 * server stops the engine calls [worldStopped], which drops the used regions and keeps the ones registered for the world about to open
 * (the pending run's dungeon, a spike's dev region). So a run's dungeon never leaks into the next boss practice, and a region registered a
 * moment before `SimWorld.open` or `rebuild` is not swept away by the old server's stop.
 */
object WorldRegions {
    private val list = CopyOnWriteArrayList<WorldRegion>()
    private val used = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    val regions: List<WorldRegion> get() = list

    /** Adds (or replaces, by name) a region for the NEXT chunk generation. */
    fun register(region: WorldRegion) { list.removeIf { it.name == region.name }; used.remove(region.name); list += region }
    fun unregister(name: String) { list.removeIf { it.name == name }; used.remove(name) }
    fun clear() { list.clear(); used.clear() }

    /** Notes that [name] has written into the running world (called by [fillAll]; tests call it directly). */
    internal fun noteUsed(name: String) { used.add(name) }

    /** The sim server stopped: forget the regions its world used, keep the ones registered for the next world. Engine calls this from `SERVER_STOPPED`. */
    fun worldStopped() { list.removeIf { it.name in used }; used.clear() }

    /** Fills a chunk from every region that touches it. Worker threads. */
    fun fillAll(chunk: ChunkAccess) {
        val x = chunk.pos.minBlockX; val z = chunk.pos.minBlockZ
        var filled = false
        for (r in list) if (r.intersects(x, z)) { r.fill(chunk); noteUsed(r.name); filled = true }
        // One prime after the last pass: a chunk two regions share needs it once, and a dungeon-only chunk needs it too.
        if (filled) net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(
            net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG))
    }

    /** Resyncs every region; returns the total blocks fixed. Server thread. */
    fun resyncAll(level: ServerLevel): Int = list.sumOf { it.resync(level) }
}
