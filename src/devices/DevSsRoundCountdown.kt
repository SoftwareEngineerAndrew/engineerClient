package com.engineerclient.devices

import com.engineerclient.EngineerClient.mc
import com.adm.events.LevelEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.utils.ServerUtils
import com.adm.utils.render.drawText
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.min

/** Over the first button of Simon Says rounds 2-4: the time until a click sent now lands as the buttons come back. */
object DevSsRoundCountdown {
    private const val TICK_NS = 50_000_000L
    private const val GO_NS = 1_000_000_000L
    private const val CLOCK_TICKS = 20

    private var tick = 0L
    private val arrivals = ArrayDeque<Pair<Long, Long>>()
    private var showStart = -1L
    private var lastLamp = -100L
    private var lamps = 0
    private var expected = 0
    private var firstLamp: BlockPos? = null
    @Volatile private var sendAt = 0L
    @Volatile private var button: BlockPos? = null

    fun install() {
        on<LevelEvent.Load> { reset() }
        on<TickEvent.Server> { serverTick() }
        onReceive<ClientboundBlockUpdatePacket> { block(pos, blockState) }
        onReceive<ClientboundSectionBlocksUpdatePacket> { runUpdates { pos, state -> block(pos, state) } }
        on<RenderExtractEvent> {
            if (DungeonUtils.getF7Phase() != M7Phases.P3) return@on
            val at = sendAt
            if (at == 0L) return@on
            val left = at - System.nanoTime()
            val lamp = DevSimonSays.clickInOrder.firstOrNull() ?: button ?: return@on
            if (left < -GO_NS || (left <= 0 && DevSimonSays.clickNeeded > 0)) return@on
            val ticks = (left + TICK_NS - 1) / TICK_NS
            val text = when {
                left <= 0 -> "§a§lGO!"
                Devices.ssCountdownSeconds -> "§b" + String.format(Locale.ROOT, "%.2f", ticks * 0.05)
                else -> "§b$ticks"
            }
            drawText(text, Vec3(lamp.x - 0.4, lamp.y + 0.75, lamp.z + 0.5), 0.8f, false)
        }
        // The gate is only checked again on boss entry and setting changes, so it can't wait for P3 itself.
        SubscribeWhile.add(this, ::reset, {}) { Devices.ssRoundCountdownOn && DungeonUtils.inBoss && DungeonUtils.floor?.floorNumber == 7 }
    }

    @Synchronized private fun serverTick() {
        tick++
        arrivals.addLast(tick to System.nanoTime())
        while (arrivals.size > CLOCK_TICKS) arrivals.removeFirst()
        // No lamp 8 after the last: the show is over, so the buttons come back 18 after it whatever the round was.
        if (showStart >= 0 && tick > lastLamp + 8) expected = lamps
        update()
    }

    @Synchronized private fun block(pos: BlockPos, state: BlockState) {
        if (pos.x != 111 || pos.y !in 120..123 || pos.z !in 92..95 || state.block != Blocks.SEA_LANTERN || tick == lastLamp) return
        if (tick - lastLamp > 8) {
            // Rounds go up by one; after a fail it is round 1 again, which the end of the show corrects.
            expected = if (showStart >= 0 && lamps < 4) lamps + 1 else 1
            showStart = tick; lamps = 0; firstLamp = pos.immutable()
        }
        lamps++
        lastLamp = tick
        update()
    }

    /** The buttons come back 18 after the last lamp; sent a round trip early, a click lands then. */
    private fun update() {
        if (showStart < 0 || maxOf(lamps, expected) < 2) { sendAt = 0L; return }
        val backAt = maxOf(lastLamp, showStart + 8L * (maxOf(expected, lamps) - 1)) + 18
        val anchor = arrivals.minOfOrNull { it.second - it.first * TICK_NS } ?: return
        sendAt = anchor + (backAt + 1) * TICK_NS - rtt() * 1_000_000L
        button = firstLamp
    }

    /** The fastest of the last few pings: a spike then shows the click late rather than early. */
    private fun rtt(): Long {
        val log = mc.debugOverlay.pingLogger
        val n = min(log.size(), 5)
        if (n == 0) return ServerUtils.averagePing.toLong()
        return (0 until n).minOf { log.get(it) }
    }

    @Synchronized private fun reset() {
        arrivals.clear(); showStart = -1L; lastLamp = -100L; lamps = 0; expected = 0
        firstLamp = null; button = null; sendAt = 0L
    }
}
