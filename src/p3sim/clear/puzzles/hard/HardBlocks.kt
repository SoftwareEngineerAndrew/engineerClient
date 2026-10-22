package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.Arena
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.run.RunBlocks
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ConcurrentHashMap

/**
 * How the hard puzzles and the trap room write blocks. A room's block states are strings in its own canonical frame; the placement
 * turns them (`quarterTurns`) and moves them to the world. Writes tell the clients and run no block logic at all: no neighbour
 * updates and no `onPlace`, so a piston placed beside a redstone block does not start moving by itself and placed water does not
 * schedule vanilla flow on top of the puzzle's own (docs/ads/ARCHITECTURE.md 7 allows only `RunBlocks` for run blocks; this is
 * the same call with `UPDATE_SKIP_ON_PLACE` added, which `RunBlocks.set` cannot be told to do). Server thread only.
 */
object HardBlocks {
    const val FLAGS = RunBlocks.QUIET

    private val parsed = ConcurrentHashMap<String, BlockState>()

    /** The block state a string names, or null when it does not parse (the caller skips it). */
    fun parse(s: String): BlockState? {
        parsed[s]?.let { return it }
        val st = try { BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState() } catch (e: Exception) { return null }
        parsed[s] = st
        return st
    }

    fun rotation(quarterTurns: Int): Rotation = when (((quarterTurns % 4) + 4) % 4) {
        1 -> Rotation.CLOCKWISE_90
        2 -> Rotation.CLOCKWISE_180
        3 -> Rotation.COUNTERCLOCKWISE_90
        else -> Rotation.NONE
    }

    /** The state turned for a placement. */
    fun state(s: String, quarterTurns: Int): BlockState? = parse(s)?.rotate(rotation(quarterTurns))

    /** Writes one world block. True when it changed. */
    fun setWorld(x: Int, y: Int, z: Int, state: BlockState): Boolean {
        return RunBlocks.set(x, y, z, state, FLAGS)
    }

    /** Applies canonical-frame edits to the world through a placement. Returns how many blocks changed. */
    fun apply(placement: Placement, edits: List<BlockEdit>): Int {
        var n = 0
        for (e in edits) {
            val st = state(e.state, placement.quarterTurns) ?: continue
            val w = placement.toWorld(e.x, e.y, e.z)
            if (setWorld(w.x, w.y, w.z, st)) n++
        }
        return n
    }
}
