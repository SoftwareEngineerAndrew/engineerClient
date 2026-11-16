package com.engineerclient.dungeon

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.SetSlotEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Colors
import com.adm.utils.itemId
import com.adm.utils.lore
import com.adm.utils.render.textDim
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import com.engineerclient.mixin.CarriedSlotAccessor
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BushBlock
import net.minecraft.world.level.block.CauldronBlock
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.FlowerBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.RedstoneTorchBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity
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
    private val alphaZpdb by BooleanSetting("Alpha ZPDB", false, desc = "Zero ping: blocks break the moment you hit them. Only on alpha.hypixel.net.")
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
    fun instantBreak(): Boolean = (enabled && instant && DungeonUtils.inDungeons && holding()) || zeroPing()

    @JvmStatic
    fun zeroPing(): Boolean = enabled && alphaZpdb && DungeonUtils.inDungeons && holding() && synced() && onAlpha()

    private fun holding() = mc.player?.mainHandItem?.itemId == "DUNGEONBREAKER"

    /** The server breaks with the slot it was last told about, not the one on screen. */
    private fun synced(): Boolean = (mc.gameMode as? CarriedSlotAccessor)?.carriedIndex() == mc.player?.inventory?.selectedSlot

    private fun onAlpha(): Boolean {
        if (mc.hasSingleplayerServer()) return false
        val host = mc.currentServer?.ip?.trim()?.lowercase()?.substringBefore(':')?.trimEnd('.') ?: return false
        return host == "alpha.hypixel.net" || host.endsWith(".alpha.hypixel.net")
    }

    @JvmStatic
    fun blocksAttack(): Boolean {
        val hit = mc.hitResult as? BlockHitResult ?: return false
        return hit.type == HitResult.Type.BLOCK && blocksBreak(hit.blockPos)
    }

    @JvmStatic
    fun blocksBreak(pos: BlockPos): Boolean {
        if (!enabled || !DungeonUtils.inDungeons || !holding()) return false
        val level = mc.level ?: return false
        val state = level.getBlockState(pos)
        val block = state.block
        if (zeroPing() && !canInstantMine(level, pos, state)) return true
        return (torchGuard && (block is BaseTorchBlock || holdsTorch(level, pos))) ||
            (secretGuard && (block is ChestBlock || block is LeverBlock || block is AbstractSkullBlock || holdsLever(level, pos)))
    }

    private val UNBREAKABLE = setOf(
        Blocks.BARRIER, Blocks.COMMAND_BLOCK, Blocks.IRON_BLOCK, Blocks.BEDROCK, Blocks.PISTON, Blocks.PISTON_HEAD, Blocks.MOVING_PISTON,
        Blocks.STICKY_PISTON, Blocks.TNT, Blocks.END_PORTAL, Blocks.END_PORTAL_FRAME, Blocks.END_GATEWAY, Blocks.NETHER_PORTAL, Blocks.LADDER,
        Blocks.PLAYER_HEAD, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.LEVER,
    )
    private val P3_LEVERS = setOf(
        BlockPos(94, 124, 113), BlockPos(106, 124, 113), BlockPos(23, 132, 138), BlockPos(27, 124, 127),
        BlockPos(2, 122, 55), BlockPos(14, 122, 55), BlockPos(84, 121, 34), BlockPos(86, 128, 46),
    )
    private const val REDSTONE_KEY = "fed95410-aba1-39df-9b95-1d4f361eb66e"

    /** Blocks the Dungeon Breaker can take; anything else the server puts back, so it isn't hit at all. */
    private fun canInstantMine(level: BlockGetter, pos: BlockPos, state: BlockState): Boolean {
        if (pos in P3_LEVERS || (state.`is`(Blocks.PLAYER_HEAD) && isRedstoneKey(level, pos))) return true
        val b = state.block
        return b !in UNBREAKABLE && !state.`is`(BlockTags.BUTTONS) && !state.`is`(BlockTags.COPPER_CHESTS) &&
            b !is RedstoneTorchBlock && b !is BushBlock && b !is CauldronBlock && b !is HopperBlock && b !is BaseEntityBlock &&
            b !is CropBlock && b !is FlowerBlock && b !is TrapDoorBlock
    }

    private fun isRedstoneKey(level: BlockGetter, pos: BlockPos): Boolean =
        (level.getBlockEntity(pos) as? SkullBlockEntity)?.ownerProfile?.partialProfile()?.id?.toString() == REDSTONE_KEY

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
