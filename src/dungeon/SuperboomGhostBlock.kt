package com.engineerclient.dungeon

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.ServerUtils
import com.odtheking.odin.utils.itemId
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

object SuperboomGhostBlock : Module(
    name = "Superboom Ghost Block",
    category = Category.DUNGEON,
    description = "Shows a ghost TNT where you click with Superboom until the server answers.",
    key = null,
) {
    private val margin by NumberSetting("Margin", 100, 0..500, 10, desc = "Kept this long past your ping.", unit = "ms")
    private val sound by BooleanSetting("Place Sound", true, desc = "Plays the TNT place sound.")

    private val ids = setOf("SUPERBOOM_TNT", "INFINITE_SUPERBOOM_TNT")
    private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

    @JvmField @Volatile var ghost: BlockPos? = null
    private var previous: BlockState? = null
    private var until = 0L

    @JvmStatic
    fun isGhost(pos: BlockPos, state: BlockState): Boolean = ghost.let { it != null && it == pos } && state.`is`(Blocks.TNT)

    @JvmStatic
    fun onUseItemOn(player: LocalPlayer, hand: InteractionHand, hit: BlockHitResult) {
        if (!enabled || hand != InteractionHand.MAIN_HAND || hit.type != HitResult.Type.BLOCK) return
        if (player.mainHandItem.itemId !in ids) return
        val level = mc.level ?: return
        val clicked = hit.blockPos
        if (!level.isLoaded(clicked)) return
        val pos = if (level.getBlockState(clicked).canBeReplaced()) clicked.immutable() else clicked.relative(hit.direction).immutable()
        val old = level.getBlockState(pos)
        if (!old.canBeReplaced() || pos == ghost) return
        val box = AABB(pos)
        if (player.boundingBox.intersects(box) || level.getEntities(null, box) { it.isAlive && it.blocksBuilding }.isNotEmpty()) return
        revert()
        val tnt = Blocks.TNT.defaultBlockState()
        level.setBlock(pos, tnt, FLAGS)
        ghost = pos
        previous = old
        until = System.currentTimeMillis() + ping(player) + margin
        if (sound) tnt.soundType.let {
            level.playLocalSound(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, it.placeSound, SoundSource.BLOCKS, (it.volume + 1f) / 2f, it.pitch * 0.8f, false)
        }
    }

    private fun ping(player: LocalPlayer): Int =
        ServerUtils.averagePing.takeIf { it > 0 } ?: mc.connection?.getPlayerInfo(player.uuid)?.latency ?: 0

    private fun revert() {
        val pos = ghost ?: return
        val old = previous
        forget()
        val level = mc.level ?: return
        if (old != null && level.isLoaded(pos) && level.getBlockState(pos).`is`(Blocks.TNT)) level.setBlock(pos, old, FLAGS)
    }

    private fun forget() {
        ghost = null
        previous = null
    }

    override fun onDisable() {
        revert()
        super.onDisable()
    }

    init {
        on<TickEvent.End> { if (ghost != null && System.currentTimeMillis() >= until) revert() }
        on<LevelEvent.Unload> { forget() }
        on<LevelEvent.Load> { forget() }

        onReceive<ClientboundBlockUpdatePacket> {
            val p = pos
            if (ghost == p) mc.execute { if (ghost == p) forget() }
        }
        onReceive<ClientboundSectionBlocksUpdatePacket> {
            val g = ghost ?: return@onReceive
            var hit = false
            runUpdates { p, _ -> if (p == g) hit = true }
            if (hit) mc.execute { if (ghost == g) forget() }
        }
    }
}
