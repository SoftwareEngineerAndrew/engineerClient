package com.engineerclient.dungeon

import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.BaseTorchBlock
import net.minecraft.world.level.block.RedstoneWallTorchBlock
import net.minecraft.world.level.block.WallTorchBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

object DungeonBreakerTorchGuard : Module(
    name = "Dungeon Breaker Torch Guard",
    category = Category.DUNGEON,
    description = "Stops the Dungeon Breaker from hitting torches and the blocks they hang on.",
    key = null,
) {
    @JvmStatic
    fun blocksAttack(): Boolean {
        if (!enabled || !DungeonUtils.inDungeons) return false
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val hit = mc.hitResult as? BlockHitResult ?: return false
        if (hit.type != HitResult.Type.BLOCK || player.mainHandItem.itemId != "DUNGEONBREAKER") return false
        val pos = hit.blockPos
        return level.getBlockState(pos).block is BaseTorchBlock || holdsTorch(level, pos)
    }

    private fun wallFacing(state: BlockState): Direction? = when (state.block) {
        is WallTorchBlock -> state.getValue(WallTorchBlock.FACING)
        is RedstoneWallTorchBlock -> state.getValue(RedstoneWallTorchBlock.FACING)
        else -> null
    }

    private fun holdsTorch(level: BlockGetter, pos: BlockPos): Boolean {
        val above = level.getBlockState(pos.above())
        if (above.block is BaseTorchBlock && wallFacing(above) == null) return true
        return Direction.Plane.HORIZONTAL.any { wallFacing(level.getBlockState(pos.relative(it))) == it }
    }
}
