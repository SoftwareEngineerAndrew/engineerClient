package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.boss.SimonSays
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * engineerClient's Simon Says solver, in Odin's Simon Says: Odin keeps drawing the boxes, blocking
 * wrong clicks, announcing and playing its sounds, but the answer it does that from is
 * [SimonSaysSolver]'s. Odin's own working-out (its block and tick listeners) is switched off by
 * `SimonSaysMixin` while this is on, and the answer written into its fields here.
 *
 * Fixes over Odin's: a re-skip after a fail (Odin keeps the stray light, as it only looks for one
 * after a world load - the start button never shows as pressed), a 2-light skip (Odin reverses
 * it), a flash before the show, a lagging last light, and lights counted as they come on.
 */
object OdinSimonSays {

    private val better = BooleanSetting("Better Solver", true,
        desc = "engineerClient's solver: handles skips after a fail, 2-light skips, flashes before the show and lag. Odin still draws, blocks and announces from it.")

    /** On, and Odin's Simon Says with it: Odin's own working-out is off. Read by SimonSaysMixin. */
    @JvmStatic
    fun active(): Boolean = SimonSays.enabled && better.value

    private val solver = SimonSaysSolver<BlockPos>()
    private var tick = 0L

    private val GRID = (120..123).flatMap { y -> (92..95).map { z -> BlockPos(110, y, z) } }

    private val odinOrder by lazy { SimonSays::class.java.getDeclaredField("clickInOrder").apply { isAccessible = true } }
    private val odinNeeded by lazy { SimonSays::class.java.getDeclaredField("clickNeeded").apply { isAccessible = true } }

    /** Adds the setting to Odin's module; before OdinSplitsLook.install, which re-reads Odin's config. */
    fun install() {
        SimonSays.registerSetting(better)
        on<TickEvent.Server> { tick++ }
        on<LevelEvent.Load> { solver.reset() }
        on<BlockUpdateEvent> {
            if (!active() || SimonSaysPractice.practicing || DungeonUtils.getF7Phase() != M7Phases.P3) return@on
            if (pos.y !in 120..123 || pos.z !in 92..95) return@on
            EngineerClient.safely("ss solver") {
                when (pos.x) {
                    111 -> when {
                        updated.block == Blocks.SEA_LANTERN && old.block != Blocks.SEA_LANTERN -> solver.lightOn(pos.immutable(), tick)
                        old.block == Blocks.SEA_LANTERN && updated.block != Blocks.SEA_LANTERN -> solver.lightOff(pos.immutable(), tick)
                        else -> return@safely
                    }
                    110 -> {
                        val level = EngineerClient.mc.level ?: return@safely
                        // Odin's event comes before the change is applied: the grid as it was.
                        when {
                            updated.block is ButtonBlock && old.block !is ButtonBlock ->
                                if (GRID.count { level.getBlockState(it).block is ButtonBlock } >= 8) solver.buttonsUp()
                            updated.isAir && !old.isAir ->
                                if (GRID.count { level.getBlockState(it).isAir } > 8) solver.buttonsGone()
                            updated.block is ButtonBlock && old.block is ButtonBlock &&
                                updated.getValue(BlockStateProperties.POWERED) && !old.getValue(BlockStateProperties.POWERED) ->
                                solver.pressed(pos.east())
                            else -> return@safely
                        }
                    }
                    else -> return@safely
                }
                sync()
            }
        }
        EventBus.subscribe(this)
    }



    /** Odin's boxes, Block Wrong Clicks and announcements read these. */
    private fun sync() {
        @Suppress("UNCHECKED_CAST")
        val order = odinOrder.get(null) as ArrayList<BlockPos>
        order.clear(); order.addAll(solver.answer)
        odinNeeded.setInt(null, solver.next)
    }
}
