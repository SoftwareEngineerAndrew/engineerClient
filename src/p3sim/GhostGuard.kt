package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.world.level.block.state.BlockState

/**
 * The host's game checked against the sim's server around you: a block your game shows differently from the
 * server for two checks in a row (0.75 s, past any hit still on its way) is a ghost, sent again and logged.
 */
object GhostGuard {
    private val mc get() = Minecraft.getInstance()
    private const val EVERY = 15
    private const val R = 5
    private var ticks = 0

    /** Server thread: last check's differences, by the state your game showed. */
    private var suspects: Map<BlockPos, BlockState>? = null

    /** Client tick: your game's blocks around you, compared on the server's thread. */
    fun clientTick() {
        if (!P3Sim.inSim || SimServer.level == null) return
        if (++ticks % EVERY != 0) return
        val p = mc.player ?: return
        val level = mc.level ?: return
        val c = p.blockPosition()
        val snap = HashMap<BlockPos, BlockState>(1200)
        for (y in -3..4) for (z in -R..R) for (x in -R..R) {
            val pos = c.offset(x, y, z)
            snap[pos] = level.getBlockState(pos)
        }
        SimServer.run("ghost check") { compare(snap) }
    }

    private fun compare(snap: Map<BlockPos, BlockState>) {
        val level = SimServer.level ?: return
        val player = SimServer.player ?: return
        val diff = snap.filter { (pos, s) -> level.getBlockState(pos) != s }
        val last = suspects.orEmpty()
        val ghosts = diff.filter { (pos, s) -> last[pos] == s }
        suspects = diff - ghosts.keys
        if (ghosts.isEmpty()) return
        ghosts.keys.forEach { player.connection.send(ClientboundBlockUpdatePacket(level, it)) }
        EngineerClient.logger.info("[p3sim] ghost blocks fixed ({}): {}", ghosts.size,
            ghosts.entries.take(8).joinToString("; ") { (pos, s) -> "${pos.x},${pos.y},${pos.z} game ${s.block.descriptionId.substringAfterLast('.')} server ${level.getBlockState(pos).block.descriptionId.substringAfterLast('.')}" } +
                " | phase ${Fight.phase?.javaClass?.simpleName} practice ${Practice.active} n ${Fight.serverTick}")
    }
}
