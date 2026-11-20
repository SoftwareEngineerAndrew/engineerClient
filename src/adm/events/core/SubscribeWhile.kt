package com.adm.events.core

/** Keeps add-on listeners on the bus only while they can act; checked again whenever a module or a switch changes. */
object SubscribeWhile {
    private class Gate(val subscriber: Any, val off: (() -> Unit)?, val want: () -> Boolean, val on: (() -> Unit)? = null)

    private val gates = ArrayList<Gate>()
    private val watchers = ArrayList<() -> Unit>()

    /** [off] runs when [subscriber] leaves the bus, to drop state its listeners would have cleared. */
    fun add(subscriber: Any, off: (() -> Unit)? = null, want: () -> Boolean) {
        val gate = Gate(subscriber, off, want)
        gates += gate
        update(gate)
    }

    /** [action] runs now and whenever a module or a switch changes, to recompute flags read every frame. */
    fun watch(action: () -> Unit) {
        watchers += action
        action()
    }

    /** [on] runs when [subscriber] joins the bus, [off] when it leaves. */
    fun add(subscriber: Any, on: () -> Unit, off: () -> Unit, want: () -> Boolean) {
        val gate = Gate(subscriber, off, want, on)
        gates += gate
        update(gate)
    }

    @JvmStatic
    fun changed() {
        for (i in watchers.indices) watchers[i]()
        for (i in gates.indices) update(gates[i])
    }

    private fun update(gate: Gate) {
        val want = gate.want()
        val on = gate.subscriber in EventBus.activeSubscribers
        if (want && !on) {
            EventBus.subscribe(gate.subscriber)
            gate.on?.invoke()
        }
        else if (!want && on) {
            EventBus.unsubscribe(gate.subscriber)
            gate.off?.invoke()
        }
    }
}
