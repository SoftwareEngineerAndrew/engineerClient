package com.adm.utils

import com.adm.AdmMod.mc
import com.adm.events.TickEvent
import com.adm.events.core.onReceive
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket
import net.minecraft.util.Util
import kotlin.math.min

object ServerUtils {
    @JvmField @Volatile var lastPacketAt = System.currentTimeMillis()
    private const val SECOND_NANOS = 1_000_000_000L
    private const val BURST_GAP_NANOS = 40_000_000L
    private const val PING_WAIT_NANOS = 250_000_000L

    private val tickTimes = LongArray(64)
    @Volatile
    private var tickCount = 0
    @Volatile
    private var worldStart = 0L

    val serverTicks: Int
        get() = tickCount

    private var pingsThisWorld = false
    private var lastBurst = 0L
    private var burstTicks = 0
    private var lastGameTime = -1L

    val averageTps: Float
        get() {
            val now = System.nanoTime()
            val count = tickCount
            var ticks = 0
            for (i in 1..minOf(count, tickTimes.size)) {
                if (now - tickTimes[(count - i) and 63] > SECOND_NANOS) break
                ticks++
            }
            val span = now - worldStart
            if (span < SECOND_NANOS) return if (span < SECOND_NANOS / 4) 20f else (ticks * SECOND_NANOS / span.toFloat()).coerceIn(0f, 20f)
            return ticks.coerceAtMost(20).toFloat()
        }

    // Hypixel sends a ping packet every tick; other servers only flush a burst of packets each tick and a time update each second.
    @JvmStatic
    fun onPacket(packet: Packet<*>) {
        lastPacketAt = System.currentTimeMillis()
        val now = System.nanoTime()
        when (packet) {
            is ClientboundPingPacket -> if (packet.id != 0) {
                pingsThisWorld = true
                serverTick(now)
                return
            }
            is ClientboundLoginPacket, is ClientboundRespawnPacket -> {
                pingsThisWorld = false
                lastGameTime = -1L
                burstTicks = 0
                worldStart = now
                return
            }
        }
        if (pingsThisWorld || now - worldStart < PING_WAIT_NANOS) return
        if (packet is ClientboundSetTimePacket) {
            val gameTime = packet.gameTime()
            if (lastGameTime >= 0) repeat((gameTime - lastGameTime - burstTicks).coerceIn(0L, 40L).toInt()) { serverTick(now) }
            lastGameTime = gameTime
            burstTicks = 0
            lastBurst = now
            return
        }
        if (now - lastBurst < BURST_GAP_NANOS) return
        lastBurst = now
        burstTicks++
        serverTick(now)
    }

    private fun serverTick(now: Long) {
        val count = tickCount
        tickTimes[count and 63] = now
        tickCount = count + 1
        TickEvent.Server.postAndCatch()
    }

    var currentPing: Int = 0
        private set

    var averagePing: Int = 0
        private set

    private const val TPS_HISTORY = 20

    private val tpsLog = FloatArray(TPS_HISTORY)
    private var tpsIndex = 0
    private var tpsCount = 0

    private fun addTpsSample(tps: Float) {
        tpsLog[tpsIndex] = tps
        tpsIndex = (tpsIndex + 1) % TPS_HISTORY
        if (tpsCount < TPS_HISTORY) tpsCount++
    }

    fun getTpsString(): String {
        val tps = averageTps
        if (tpsCount == 0) return "Current: ${tps.toFixed(1)}"
        val log = tpsLog.copyOf(tpsCount)
        return "Current: ${tps.toFixed(1)} (max/min/avg) ${log.max().toFixed(1)}/${log.min().toFixed(1)}/${log.average().toFloat().toFixed(1)}"
    }

    init {
        onReceive<ClientboundSetTimePacket> { addTpsSample(averageTps) }

        onReceive<ClientboundPongResponsePacket> {
            currentPing = (Util.getMillis() - time).toInt().coerceAtLeast(0)

            val pingLog = mc.debugOverlay.pingLogger

            val sampleSize = min(pingLog.size(), 20)

            if (sampleSize == 0) {
                averagePing = currentPing
                return@onReceive
            }

            var total = 0L
            for (i in 1..sampleSize) {
                total += pingLog.get(pingLog.size() - i)
            }

            averagePing = (total / sampleSize).toInt()
        }
    }
}
