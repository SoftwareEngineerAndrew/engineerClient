package com.engineerclient.practice

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import java.util.concurrent.ConcurrentHashMap

/**
 * A [SimonSaysSolver] fed from F7's real device: its block packets as they arrive, stamped with the server tick,
 * and the grid's block changes on the client. Odin-free: [OdinSimonSays] feeds one from Odin's events (Odin's Simon
 * Says draws from it), and ADS's own Simon Says (com.engineerclient.devices.AdsSimonSays) one from its mixins.
 */
class SimonSaysFeed {
    val solver = SimonSaysSolver<BlockPos>()

    /** A lamp packet: what it set ([lit]) and what the lamp was before it ([was]). */
    class Lamp(val pos: BlockPos, val lit: Boolean, val was: Boolean)

    /**
     * Each lamp as its packets left it, in the order they arrived. The world can't say what a lamp was before a
     * packet: since 26.x packets are applied from their own queue (PacketProcessor), apart from the tasks a handler
     * queues, so by the time the client thread sees it the packet may already be in the world (every lighting would
     * look like a re-send, and nothing would ever be drawn).
     */
    private val lampLit = ConcurrentHashMap<BlockPos, Boolean>()
    /** The server tick each button's last packet arrived in (its block change comes when the packet is applied). */
    private val buttonTick = ConcurrentHashMap<BlockPos, Long>()

    fun reset() { solver.reset(); lampLit.clear(); buttonTick.clear() }

    /**
     * A block packet as it arrives (the network thread), in server tick [tick]: its lamps, for [lamps] on the client
     * thread, or null when it has none. Re-sends count: a lamp sent lit while lit (cleared and relit in one tick) is
     * how a skip's chain starts show, and a block change event skips it as no change. A lamp not seen since the world
     * loaded is taken as a change (packets come for changes, re-sends aside).
     */
    fun arrived(packet: Packet<*>, tick: Long): List<Lamp>? {
        val changes = when (packet) {
            is ClientboundBlockUpdatePacket -> if (onDevice(packet.pos)) listOf(packet.pos.immutable() to packet.blockState) else return null
            is ClientboundSectionBlocksUpdatePacket -> ArrayList<Pair<BlockPos, BlockState>>().also { l ->
                packet.runUpdates { pos, state -> if (onDevice(pos)) l += pos.immutable() to state }
            }.ifEmpty { return null }
            else -> return null
        }
        val lamps = changes.filter { it.first.x == 111 }.map { (pos, state) ->
            val lit = state.block == Blocks.SEA_LANTERN
            Lamp(pos, lit, lampLit.put(pos, lit) ?: !lit)
        }
        for ((pos, _) in changes) if (pos.x == 110) buttonTick[pos] = tick
        return lamps.ifEmpty { null }
    }

    /** [arrived]'s lamps, on the client thread. */
    fun lamps(lamps: List<Lamp>, tick: Long) {
        for (l in lamps) solver.lamp(l.pos, l.lit, l.was, tick)
    }

    /** Every client tick, a server tick behind: that tick's lamp packets may still be on their way to the client thread. */
    fun serverTick(tick: Long) = solver.serverTick(tick)

    /**
     * A grid block about to change on the client ([level] still has [old]): the buttons coming back, going away, or
     * one pressed. False if it was none of those.
     */
    fun blockChanged(level: Level, pos: BlockPos, old: BlockState, updated: BlockState, tick: Long): Boolean {
        if (pos.x != 110 || pos.y !in 120..123 || pos.z !in 92..95) return false
        val packetTick = buttonTick[pos] ?: tick
        when {
            updated.block is ButtonBlock && old.block !is ButtonBlock ->
                if (GRID.count { level.getBlockState(it).block is ButtonBlock } >= 8) solver.buttonsUp(packetTick)
            updated.isAir && !old.isAir ->
                if (GRID.count { level.getBlockState(it).isAir } > 8) solver.buttonsGone(packetTick)
            updated.block is ButtonBlock && old.block is ButtonBlock &&
                updated.getValue(BlockStateProperties.POWERED) && !old.getValue(BlockStateProperties.POWERED) ->
                solver.pressed(pos.east(), tick) // own click: the client presses it before the server answers
            else -> return false
        }
        return true
    }

    companion object {
        /** The grid's buttons (x 110); each one's lamp is a block east. */
        val GRID = (120..123).flatMap { y -> (92..95).map { z -> BlockPos(110, y, z) } }

        fun onDevice(pos: BlockPos) = (pos.x == 110 || pos.x == 111) && pos.y in 120..123 && pos.z in 92..95
    }
}
