package com.engineerclient.dungeonpack.rooms

import com.engineerclient.EngineerClient
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ConcurrentHashMap

/** Block states of the room pack as Minecraft states, parsed once and shared (the chunk generator's workers read this). */
object BlockStates {
    private val parsed = ConcurrentHashMap<String, BlockState>()

    fun parse(state: String): BlockState = parsed.computeIfAbsent(state) {
        try { net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK, it, false).blockState() }
        catch (t: Throwable) { com.engineerclient.EngineerClient.logger.warn("[ads] unknown block state {}", it); net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() }
    }

    /** [state] turned [quarterTurns] clockwise (`BlockState.rotate`, FORMAT-rooms 3.3). */
    fun rotated(state: String, quarterTurns: Int): BlockState {
        val s = parse(state)
        return if (quarterTurns.mod(4) == 0) s else s.rotate(rotation(quarterTurns))
    }

    fun rotation(quarterTurns: Int): Rotation = when (quarterTurns.mod(4)) {
        0 -> Rotation.NONE
        1 -> Rotation.CLOCKWISE_90
        2 -> Rotation.CLOCKWISE_180
        else -> Rotation.COUNTERCLOCKWISE_90
    }
}

