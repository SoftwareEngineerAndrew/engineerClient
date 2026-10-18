package com.engineerclient.p3sim.run

import org.slf4j.LoggerFactory

private val busFailures = FailureLog()

/**
 * The run event bus: synchronous, single-threaded (the sim server thread), deterministic. Modules post events when they
 * change [RunState]; subscribers react in the same tick.
 *
 * Delivery rules, so that no module has to think about ordering:
 *  - Subscribers run in order of `priority` (low first), then in the order they subscribed.
 *  - A handler that posts another event does not recurse: the new event waits in a FIFO queue and is delivered after the
 *    current event has reached every subscriber. So "event A then event B" is the same order everywhere.
 *  - A handler that throws is logged and skipped; the other subscribers still get the event.
 *  - [post] must be called on the server thread ([serverThread] says what that means; the engine installs the real check).
 *
 * The run's own fan-out to the modules (`RunSystem.onEvent`, in module order) is one subscriber at priority [SYSTEMS_PRIORITY].
 */
class RunBus(
    private val serverThread: () -> Boolean = { true },
    private val onError: (RunEvent, Throwable) -> Unit = { e, t ->
        // A subscriber that fails on every event of a kind is reported in full once, then counted (see [FailureLog]).
        busFailures.admit(e.javaClass.simpleName)?.let { held -> LoggerFactory.getLogger("engineerclient").error("[ads] a subscriber failed on $e${busFailures.suffix(held)}", t) }
    },
) {
    private class Sub(val priority: Int, val seq: Int, val handler: (RunEvent) -> Unit, var active: Boolean = true)

    /** Cancels a subscription. */
    class Subscription internal constructor(private val sub: Any) {
        fun cancel() { (sub as Sub).active = false }
    }

    private var subs: List<Sub> = emptyList()
    private var seq = 0
    private val queue = ArrayDeque<RunEvent>()
    private var draining = false
    private val recent = ArrayDeque<RunEvent>()

    /** Subscribes to every event. */
    fun subscribe(priority: Int = 0, handler: (RunEvent) -> Unit): Subscription {
        val s = Sub(priority, seq++, handler)
        subs = (subs + s).sortedWith(compareBy({ it.priority }, { it.seq }))
        return Subscription(s)
    }

    /** Subscribes to events of one type. */
    inline fun <reified T : RunEvent> on(priority: Int = 0, crossinline handler: (T) -> Unit): Subscription =
        subscribe(priority) { if (it is T) handler(it) }

    /** Posts [event]: delivered now if no event is being delivered, else queued behind the ones already waiting. */
    fun post(event: RunEvent) {
        check(serverThread()) { "RunBus.post must be called on the sim server thread" }
        if (queue.size >= MAX_QUEUE) { LoggerFactory.getLogger("engineerclient").error("[ads] event queue overflow, dropping $event"); return }
        queue.addLast(event)
        if (draining) return
        draining = true
        try {
            while (queue.isNotEmpty()) {
                val e = queue.removeFirst()
                remember(e)
                for (s in subs) if (s.active) try { s.handler(e) } catch (t: Throwable) { onError(e, t) }
            }
        } finally { draining = false }
    }

    private fun remember(e: RunEvent) {
        recent.addLast(e)
        while (recent.size > KEEP) recent.removeFirst()
    }

    /** The last events delivered (for `/ads dev` tools and logs), oldest first. */
    fun recentEvents(): List<RunEvent> = recent.toList()

    companion object {
        /** Subscriber priority of the run's fan-out to the modules. */
        const val SYSTEMS_PRIORITY = 0
        private const val MAX_QUEUE = 100_000
        private const val KEEP = 200
    }
}
