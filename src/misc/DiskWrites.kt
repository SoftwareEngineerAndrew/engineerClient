package com.engineerclient.misc

import com.engineerclient.EngineerClient
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Small file writes off the game thread, one at a time in the order asked; whatever is queued is written before the game exits. */
object DiskWrites {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "engineerclient-disk-writes").apply { isDaemon = true } }

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }, "engineerclient-disk-writes-flush"))
    }

    fun write(task: () -> Unit) {
        try {
            executor.execute { run(task) }
        } catch (_: RejectedExecutionException) {
            run(task)
        }
    }

    /** Queued after everything before it, and waited for. */
    fun writeNow(task: () -> Unit) {
        try {
            executor.submit { run(task) }.get(5, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) {
            run(task)
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ec] disk write didn't finish", t)
        }
    }

    private fun run(task: () -> Unit) {
        try { task() } catch (t: Throwable) { EngineerClient.logger.warn("[ec] disk write failed", t) }
    }
}
