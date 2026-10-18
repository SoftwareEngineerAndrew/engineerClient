package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.Arena
import com.engineerclient.p3sim.pack.DungeonFrame
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.ChunkAccess

/**
 * The boss arena as a [WorldRegion], for a world that also holds a dungeon. It is the arena exactly as the boss practice has it
 * (`arena.bin` with the corrections of `arena-fixes.json`) with one difference: the extension (`arena-ext.bin`, the thicker east wall
 * and the north-west column) is CLIPPED to x, z >= [CLIP_MIN] (`ArenaFixes.patch` reads [active]). Unclipped it wrote 2,156 real blocks at
 * x, z -16..-10, which is the south-east corner of dungeon tile (5,5) (ARCHITECTURE 2).
 *
 * Registered with [WorldRegions] by the run launcher (and by the engine spikes) before the world's chunks generate. While it is registered
 * `Arena.fill` leaves the arena to it (`WorldRegions.fillAll` calls [fill]); with no dungeon in the world nothing is registered and the
 * boss practice fills the arena itself, unclipped, byte for byte as before.
 *
 * Resync: the arena compares only its own (clipped) box. Nothing in the clear changes an arena block, so [resync] does nothing until the
 * boss has started ([markDirty]); that keeps a savepoint restore in the clear from comparing 6.5 million cells for nothing.
 */
object ArenaRegion : WorldRegion {
    const val NAME = "arena"

    /** The smallest x and z the arena may write in a world with a dungeon: [DungeonFrame.ARENA_MIN]. */
    const val CLIP_MIN = DungeonFrame.ARENA_MIN

    override val name = NAME
    override val minX = CLIP_MIN
    override val minZ = CLIP_MIN
    /** The arena's box ends at x 143, z 159 (the extension at 144 and 160); the rectangle is a margin beyond that, `Arena.fillBlocks` checks the real box. */
    override val maxX = 176
    override val maxZ = 192

    /** True while the arena region is registered: the arena is then clipped and `Arena.fill` leaves it to [fill]. Read on worker threads. */
    val active: Boolean get() = WorldRegions.regions.any { it.name == NAME }

    @Volatile private var dirty = false

    /** The boss has started (or an arena block changed some other way): the next [resync] compares the arena. */
    fun markDirty() { dirty = true }

    /** The lowest x or z the extension box starts at when its own minimum is [extMin]: clipped to [CLIP_MIN] while a dungeon is in the world. */
    fun clippedMin(extMin: Int, clip: Boolean): Int = if (clip) maxOf(extMin, CLIP_MIN) else extMin

    /** Whether the extension's block at ([x], [z]) is written: not when it lies in the dungeon's corner and the arena is [clip]ped. */
    fun keeps(x: Int, z: Int, clip: Boolean): Boolean = !clip || (x >= CLIP_MIN && z >= CLIP_MIN)

    /** A new world: nothing of the last one's boss to compare. */
    fun reset() { dirty = false }

    /** Registers the region for the next world. */
    fun register() { WorldRegions.register(this) }

    override fun fill(chunk: ChunkAccess) { Arena.fillBlocks(chunk) }

    override fun resync(level: ServerLevel): Int {
        if (!dirty) return 0
        dirty = false
        return Arena.resync(level)
    }
}
