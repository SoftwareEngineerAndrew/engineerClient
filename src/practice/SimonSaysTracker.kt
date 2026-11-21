package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.adm.events.BlockUpdateEvent
import com.adm.events.LevelEvent
import com.adm.events.TickEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.engineerclient.devices.DevSimonSays
import com.engineerclient.devices.Devices
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * The Simon Says solver behind Devices' Simon Says ([com.engineerclient.devices.DevSimonSays]): it draws, blocks and announces
 * from the answer worked out here by [SimonSaysSolver].
 *
 * Fixes over ADM's: a re-skip after a fail (ADM keeps the stray light, as it only looks for one
 * after a world load - the start button never shows as pressed), a 2-light skip (ADM reverses
 * it), a flash before the show, a lagging last light, lights counted as they come on, and a skip
 * whose last round is still showing when the buttons come back (triple skips, delayed shows).
 */
object SimonSaysTracker {

    @JvmStatic
    fun active(): Boolean = Devices.simonSaysOn

    private val solver = SimonSaysSolver<BlockPos>()

    /** The round showing or being put in, as its lights so far (0 between rounds), and how many of it are pressed: how far it has got. */
    val shownRound: Int get() = solver.answer.size
    val pressedInRound: Int get() = solver.next
    @Volatile private var tick = 0L
    /**
     * Each lamp as its packets left it, in the order they arrived. The world can't say what a lamp was before a
     * packet: since 26.x packets are applied from their own queue (PacketProcessor), apart from the tasks this
     * handler queues, so by the time the handler runs the packet may already be in the world (every lighting would
     * look like a re-send, and nothing would ever be drawn).
     */
    private val lampLit = java.util.concurrent.ConcurrentHashMap<BlockPos, Boolean>()
    /** The server tick each button's last packet arrived in (its block event comes when the packet is applied). */
    private val buttonTick = java.util.concurrent.ConcurrentHashMap<BlockPos, Long>()

    private val GRID = (120..123).flatMap { y -> (92..95).map { z -> BlockPos(110, y, z) } }

    fun install() {
        on<TickEvent.Server> { tick++ }
        on<LevelEvent.Load> { reset() }
        // Lamps come from their block packets, re-sends included: a lamp sent lit while lit (cleared and
        // relit in one tick) is how a skip's chain starts show, and ADM's BlockUpdateEvent skips it as no
        // change. Stamped with the server tick on arrival, and what the lamp was taken from the packets before
        // it (in arrival order), then handled on the client thread.
        onReceive<ClientboundBlockUpdatePacket> { lampPacket(this) }
        onReceive<ClientboundSectionBlocksUpdatePacket> { lampPacket(this) }
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
                // ADM's event comes before the change is applied: the grid as it was.
                val packetTick = buttonTick[pos] ?: tick
                when {
                    updated.block is ButtonBlock && old.block !is ButtonBlock ->
                        if (GRID.count { level.getBlockState(it).block is ButtonBlock } >= 8) solver.buttonsUp(packetTick)
                    updated.isAir && !old.isAir ->
                        if (GRID.count { level.getBlockState(it).isAir } > 8) {
                            solver.buttonsGone(packetTick)
                            if (solver.answer.isEmpty()) com.engineerclient.devices.DevSimonSays.deviceStopped()
                        }
                    updated.block is ButtonBlock && old.block is ButtonBlock &&
                        updated.getValue(BlockStateProperties.POWERED) && !old.getValue(BlockStateProperties.POWERED) ->
                        solver.pressed(pos.east(), tick) // own click: the client presses it before the server answers
                    else -> return@safely
                }
                sync()
            }
        }
        SubscribeWhile.add(this, ::reset, {}) { active() }
    }

    private fun reset() { solver.reset(); lampLit.clear(); buttonTick.clear() }

    private fun onDevice(pos: BlockPos) = (pos.x == 110 || pos.x == 111) && pos.y in 120..123 && pos.z in 92..95

    /** The boxes, Block Wrong Clicks and announcements read these. */
    private fun sync() {
        DevSimonSays.clickInOrder.clear(); DevSimonSays.clickInOrder.addAll(solver.answer)
        DevSimonSays.clickNeeded = solver.next
    }

    private fun lampPacket(packet: Packet<*>) {
        if (!active() || DungeonUtils.getF7Phase() != M7Phases.P3) return
        val changes = when (val p = packet) {
            is ClientboundBlockUpdatePacket -> if (onDevice(p.pos)) listOf(p.pos.immutable() to p.blockState) else return
            is ClientboundSectionBlocksUpdatePacket -> ArrayList<Pair<BlockPos, BlockState>>().also { l ->
                p.runUpdates { pos, state -> if (onDevice(pos)) l += pos.immutable() to state }
            }.ifEmpty { return }
            else -> return
        }
        val at = tick
        // A lamp not seen since the world loaded: taken as a change (packets come for changes, re-sends aside).
        val lamps = changes.filter { it.first.x == 111 }.map { (pos, state) ->
            val lit = state.block == Blocks.SEA_LANTERN
            Triple(pos, lit, lampLit.put(pos, lit) ?: !lit)
        }
        for ((pos, _) in changes) if (pos.x == 110) buttonTick[pos] = at
        if (lamps.isEmpty()) return
        val handle = Runnable {
            if (!active() || SimonSaysPractice.practicing || DungeonUtils.getF7Phase() != M7Phases.P3) return@Runnable
            EngineerClient.safely("ss solver lamps") {
                for ((pos, lit, was) in lamps) solver.lamp(pos, lit, was, at)
                sync()
            }
        }
        if (EngineerClient.mc.isSameThread) handle.run() else EngineerClient.mc.execute(handle)
    }
}
