package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.PacketEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.boss.SimonSays
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * engineerClient's Simon Says solver, in Odin's Simon Says: Odin keeps drawing the boxes, blocking
 * wrong clicks, announcing and playing its sounds, but the answer it does that from is
 * [SimonSaysSolver]'s. Odin's own working-out (its block and tick listeners) is switched off by
 * `SimonSaysMixin` while this is on, and the answer written into its fields here.
 *
 * Fixes over Odin's: a re-skip after a fail (Odin keeps the stray light, as it only looks for one
 * after a world load - the start button never shows as pressed), a 2-light skip (Odin reverses
 * it), a flash before the show, a lagging last light, lights counted as they come on, and a skip
 * whose last round is still showing when the buttons come back (triple skips, delayed shows).
 */
object OdinSimonSays {

    private val better = BooleanSetting("Better Solver", true,
        desc = "engineerClient's solver: handles skips after a fail, 2-light skips, flashes before the show and lag. Odin still draws, blocks and announces from it.")

    /** On, and Odin's Simon Says with it: Odin's own working-out is off. Read by SimonSaysMixin. */
    @JvmStatic
    fun active(): Boolean = SimonSays.enabled && better.value

    private val solver = SimonSaysSolver<BlockPos>()
    @Volatile private var tick = 0L
    /** The server tick of the device block packet being applied (stamped when it arrived). */
    private var packetTick = 0L

    private val GRID = (120..123).flatMap { y -> (92..95).map { z -> BlockPos(110, y, z) } }

    private val odinOrder by lazy { SimonSays::class.java.getDeclaredField("clickInOrder").apply { isAccessible = true } }
    private val odinNeeded by lazy { SimonSays::class.java.getDeclaredField("clickNeeded").apply { isAccessible = true } }

    /** Adds the setting to Odin's module; before OdinSplitsLook.install, which re-reads Odin's config. */
    fun install() {
        SimonSays.registerSetting(better)
        on<TickEvent.Server> { tick++ }
        on<LevelEvent.Load> { solver.reset() }
        // Lamps come from their block packets, re-sends included: a lamp sent lit while lit (cleared and
        // relit in one tick) is how a skip's chain starts show, and Odin's BlockUpdateEvent skips it as no
        // change. Stamped with the server tick on arrival, handled on the client thread just before the
        // packet is applied, so the lamp's state then is its state before the packet.
        on<PacketEvent.Receive> {
            val changes = when (val p = packet) {
                is ClientboundBlockUpdatePacket -> if (onDevice(p.pos)) listOf(p.pos.immutable() to p.blockState) else return@on
                is ClientboundSectionBlocksUpdatePacket -> ArrayList<Pair<BlockPos, BlockState>>().also { l ->
                    p.runUpdates { pos, state -> if (onDevice(pos)) l += pos.immutable() to state }
                }.ifEmpty { return@on }
                else -> return@on
            }
            val at = tick
            val handle = Runnable {
                packetTick = at
                if (!active() || SimonSaysPractice.practicing || DungeonUtils.getF7Phase() != M7Phases.P3) return@Runnable
                val level = EngineerClient.mc.level ?: return@Runnable
                EngineerClient.safely("ss solver lamps") {
                    var any = false
                    for ((pos, state) in changes) if (pos.x == 111) {
                        solver.lamp(pos, state.block == Blocks.SEA_LANTERN, level.getBlockState(pos).block == Blocks.SEA_LANTERN, at)
                        any = true
                    }
                    if (any) sync()
                }
            }
            if (EngineerClient.mc.isSameThread) handle.run() else EngineerClient.mc.execute(handle)
        }
        on<TickEvent.End> {
            if (!active() || SimonSaysPractice.practicing || DungeonUtils.getF7Phase() != M7Phases.P3) return@on
            // A tick behind: that tick's lamp packets may still be on their way to this thread.
            EngineerClient.safely("ss solver tick") { solver.serverTick(tick - 1); sync() }
        }
        on<BlockUpdateEvent> {
            if (!active() || SimonSaysPractice.practicing || DungeonUtils.getF7Phase() != M7Phases.P3) return@on
            if (pos.x != 110 || pos.y !in 120..123 || pos.z !in 92..95) return@on
            EngineerClient.safely("ss solver") {
                val level = EngineerClient.mc.level ?: return@safely
                // Odin's event comes before the change is applied: the grid as it was.
                when {
                    updated.block is ButtonBlock && old.block !is ButtonBlock ->
                        if (GRID.count { level.getBlockState(it).block is ButtonBlock } >= 8) solver.buttonsUp(packetTick)
                    updated.isAir && !old.isAir ->
                        if (GRID.count { level.getBlockState(it).isAir } > 8) solver.buttonsGone(packetTick)
                    updated.block is ButtonBlock && old.block is ButtonBlock &&
                        updated.getValue(BlockStateProperties.POWERED) && !old.getValue(BlockStateProperties.POWERED) ->
                        solver.pressed(pos.east(), tick) // own click: the client presses it before the server answers
                    else -> return@safely
                }
                sync()
            }
        }
        EventBus.subscribe(this)
    }

    private fun onDevice(pos: BlockPos) = (pos.x == 110 || pos.x == 111) && pos.y in 120..123 && pos.z in 92..95

    /** Odin's boxes, Block Wrong Clicks and announcements read these. */
    private fun sync() {
        @Suppress("UNCHECKED_CAST")
        val order = odinOrder.get(null) as ArrayList<BlockPos>
        order.clear(); order.addAll(solver.answer)
        odinNeeded.setInt(null, solver.next)
    }
}
