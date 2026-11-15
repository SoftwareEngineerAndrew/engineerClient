package com.engineerclient.misc

import com.engineerclient.EngineerClient
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/** When the game stops ticking for [STALL_MS], every thread's stack goes to the log once, so a freeze can be traced. */
object FreezeReport {
    @Volatile private var lastTick = System.currentTimeMillis()
    @Volatile private var reported = false

    fun install() {
        ClientTickEvents.END_CLIENT_TICK.register { lastTick = System.currentTimeMillis(); reported = false }
        Thread({
            while (true) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
                val stalled = System.currentTimeMillis() - lastTick
                if (stalled < STALL_MS || reported) continue
                reported = true
                EngineerClient.safely("freeze report") { report(stalled) }
            }
        }, "engineerclient-freeze-report").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun report(stalled: Long) {
        val out = StringBuilder("[ec] game not ticking for ${stalled / 1000}s, threads:\n")
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
