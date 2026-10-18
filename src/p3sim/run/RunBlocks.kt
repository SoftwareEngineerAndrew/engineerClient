package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.Arena
import com.engineerclient.p3sim.SimServer
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState

/**
 * The one sanctioned way for run logic (doors, secrets, puzzles, traps, the blood room) to change a block of the dungeon while the
 * run is on. Server thread only.
 *
 * Do NOT use `Blocks.set` or `Arena.set` for that. `Blocks` is the boss arena's bookkeeping: it remembers every position it changed
 * and, on a restart or when the world is left, puts each one back to what the ARENA table says there. Outside the arena that table
 * says air, so a dungeon block changed through `Blocks.set` would be erased by the next boss restart. Dungeon blocks are put back
 * by a savepoint restore instead: the [WorldRegion] resync returns them to the start state and each module re-applies its own
 * changes in `RunSystem.restore` (docs/ads/ARCHITECTURE.md 7).
 *
 * The clients are told (so Odin sees the block update on the client thread); neighbours are not updated, so nothing pops off or flows.
 */
object RunBlocks {
    /** Clients told, no shape or neighbour updates: the same flags the arena uses. */
    const val FLAGS = Arena.FLAGS

    /** [FLAGS] without the block's own `onPlace`: placed water does not start vanilla flow and a piston beside a redstone block does not start moving by itself (the Water Board). */
    const val QUIET = FLAGS or net.minecraft.world.level.block.Block.UPDATE_SKIP_ON_PLACE

    /** The sim's level, or null when no sim server runs. */
    val level: ServerLevel? get() = SimServer.level

    /**
     * Whether the chunk of column ([x], [z]) is loaded now. Only the chunks near a player are (view and simulation distance are pinned to 6
     * during a run, so about 112 blocks): a bot's door three rooms away, a far room's chest or a mob list may sit in an unloaded chunk.
     * [set] and [get] on one loads it synchronously (a chunk of the dungeon is generated from the region on the way, a few ms to tens of ms);
     * code that runs every tick for far-away rooms should check this first and keep its own state in [RunState] until the chunk loads.
     */
    fun isLoaded(x: Int, z: Int): Boolean = level?.isLoaded(BlockPos(x, 64, z)) == true

    fun get(x: Int, y: Int, z: Int): BlockState? = level?.getBlockState(BlockPos(x, y, z))

    /** Sets a block (no-op when it already is [state]); true when it changed. */
    fun set(x: Int, y: Int, z: Int, state: BlockState, flags: Int = FLAGS): Boolean {
        val lv = level ?: return false
        val pos = BlockPos(x, y, z)
        if (lv.getBlockState(pos) == state) return false
        return lv.setBlock(pos, state, flags)
    }

    fun set(pos: BlockPos, state: BlockState): Boolean = set(pos.x, pos.y, pos.z, state)
}
