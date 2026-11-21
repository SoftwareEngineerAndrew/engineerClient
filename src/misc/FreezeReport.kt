package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.adm.utils.ServerUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/** When the game stops ticking or packets stop coming for [STALL_MS], every thread's stack goes to the log once, so a freeze can be traced. */
object FreezeReport {
    @Volatile private var lastTick = System.currentTimeMillis()
    @Volatile private var idleAt = System.currentTimeMillis()
    @Volatile private var reported = false
    @Volatile private var packetReportedFor = -1L

    fun install() {
        ClientTickEvents.END_CLIENT_TICK.register { lastTick = System.currentTimeMillis(); reported = false; if (EngineerClient.mc.connection == null) idleAt = lastTick }
        Thread({
            while (true) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
                val now = System.currentTimeMillis()
                if (now - lastTick >= STALL_MS && !reported) { reported = true; EngineerClient.safely("freeze report") { report("game not ticking", now - lastTick) } }
                val lastPacket = maxOf(ServerUtils.lastPacketAt, idleAt)
                if (now - lastPacket >= STALL_MS && now - lastTick < STALL_MS && packetReportedFor != lastPacket) { packetReportedFor = lastPacket; EngineerClient.safely("freeze report") { report("no packets", now - lastPacket) } }
            }
        }, "engineerclient-freeze-report").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun report(what: String, stalled: Long) {
        val out = StringBuilder("[ec] $what for ${stalled / 1000}s, threads:\n")
        val all = Thread.getAllStackTraces().entries.sortedBy { (t, _) -> if (t.name == "Render thread") 0 else 1 }
        for ((t, stack) in all) {
            if (stack.isEmpty()) continue
            out.append("\"").append(t.name).append("\" ").append(t.state).append('\n')
            for (f in stack.take(40)) out.append("    at ").append(f).append('\n')
        }
        EngineerClient.logger.warn(out.toString())
    }

    private const val STALL_MS = 10_000L
}
