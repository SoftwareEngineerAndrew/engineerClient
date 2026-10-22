package com.engineerclient.p3sim.clear.puzzles

import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ConcurrentHashMap

/**
 * Block states written as strings in a room's canonical frame (`minecraft:chest[facing=north,type=single,waterlogged=false]`), turned for
 * the way the room stands in the world. The puzzles keep a few such states of their own (a chest that faces the gate, the bars of a rail);
 * the same few lines exist for the hard puzzles in their own folder. Server thread.
 */
object PuzzleBlocks {
    private val parsed = ConcurrentHashMap<String, BlockState>()

    /** The state a string names, or null when it does not parse. */
    fun parse(s: String): BlockState? {
        parsed[s]?.let { return it }
        val st = try { BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState() } catch (e: Exception) { return null }
        parsed[s] = st
        return st
    }

    /** The clockwise quarter turns as the game's rotation. */
    fun rotation(quarterTurns: Int): Rotation = when (((quarterTurns % 4) + 4) % 4) {
        1 -> Rotation.CLOCKWISE_90
        2 -> Rotation.CLOCKWISE_180
        3 -> Rotation.COUNTERCLOCKWISE_90
        else -> Rotation.NONE
    }

    /** The state turned by [quarterTurns]. */
    fun state(s: String, quarterTurns: Int): BlockState? = parse(s)?.rotate(rotation(quarterTurns))
}
