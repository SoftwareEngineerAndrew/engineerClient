package com.engineerclient.dungeon

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.SetSlotEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.lore
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.BaseTorchBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.RedstoneWallTorchBlock
import net.minecraft.world.level.block.WallTorchBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

object Dungeonbreaker : Module(
    name = "Dungeonbreaker",
    category = Category.DUNGEON,
    description = "Dungeon Breaker torch and secret guards and charges display.",
    key = null,
) {
    private val torchGuard by BooleanSetting("Torch Guard", true, desc = "Stops the Dungeon Breaker from hitting torches and the blocks they hang on.")
    private val secretGuard by BooleanSetting("Stop Breaking Secrets", true, desc = "Stops the Dungeon Breaker from hitting chests, levers, skulls and the blocks levers hang on.")
    private val instant by BooleanSetting("Instant Break", false, desc = "No wait between blocks while you hold left click.")
    private val chargesDisplay by BooleanSetting("Charges Display", true, desc = "Shows the charges left in your Dungeon Breaker.")

    private val chargesRegex = Regex("Charges: (\\d+)/(\\d+)⸕")
    private var maxCharges = 0
    private var charges = 0

    private val hud by HUD("Breaker Display", "Shows the amount of charges left in your Dungeon Breaker.", false) {
        if (!chargesDisplay || (!it && (maxCharges == 0 || !DungeonUtils.inDungeons || (!renderFull && charges == maxCharges)))) 0 to 0
        else textDim("§e${if (it) 17 else charges}§7/§e${if (it) 20 else maxCharges}§c⸕", 0, 0, Colors.WHITE)
    }.withDependency { chargesDisplay }
    private val renderFull by BooleanSetting("Render full", false, desc = "Renders the HUD even when the Dungeon Breaker is full.").withDependency { chargesDisplay }

    init {
        on<SetSlotEvent> {
            if (!DungeonUtils.inDungeons || itemStack.itemId != "DUNGEONBREAKER") return@on
            itemStack.lore.firstNotNullOfOrNull { chargesRegex.find(it.string) }?.let { match ->
                charges = match.groupValues[1].toIntOrNull() ?: 0
                maxCharges = match.groupValues[2].toIntOrNull() ?: 0
            }
        }
    }

    @JvmStatic
    fun instantBreak(): Boolean = enabled && instant && DungeonUtils.inDungeons && mc.player?.mainHandItem?.itemId == "DUNGEONBREAKER"

    @JvmStatic
    fun blocksAttack(): Boolean {
        if (!enabled || (!torchGuard && !secretGuard) || !DungeonUtils.inDungeons) return false
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val hit = mc.hitResult as? BlockHitResult ?: return false
        if (hit.type != HitResult.Type.BLOCK || player.mainHandItem.itemId != "DUNGEONBREAKER") return false
        val pos = hit.blockPos
        val block = level.getBlockState(pos).block
        return (torchGuard && (block is BaseTorchBlock || holdsTorch(level, pos))) ||
            (secretGuard && (block is ChestBlock || block is LeverBlock || block is AbstractSkullBlock || holdsLever(level, pos)))
    }

    private fun holdsLever(level: BlockGetter, pos: BlockPos): Boolean = Direction.entries.any { dir ->
        val state = level.getBlockState(pos.relative(dir))
        state.block is LeverBlock && when (state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)) {
            AttachFace.FLOOR -> dir == Direction.UP
            AttachFace.CEILING -> dir == Direction.DOWN
            else -> state.getValue(HorizontalDirectionalBlock.FACING) == dir
        }
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
