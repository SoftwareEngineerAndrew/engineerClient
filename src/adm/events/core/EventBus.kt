package com.adm.events.core

import com.adm.events.PacketEvent
import com.adm.utils.logError
import net.minecraft.network.protocol.Packet
import net.minecraft.util.profiling.Profiler
import java.util.concurrent.ConcurrentHashMap

object EventBus {

    private val listenerArrays = HashMap<Class<out Event>, Array<ListenerEntry<out Event>>>()
    @JvmField
    val activeSubscribers: MutableSet<Any> = ConcurrentHashMap.newKeySet()
    private val subscriberClasses = HashMap<Any, Class<*>>()

    // Read from the network thread while toggles rebuild it: only ever swapped whole, never changed in place.
    @Volatile
    private var invokers: Map<Class<out Event>, Invoker> = emptyMap()

    @Synchronized
    fun subscribe(subscriber: Any) {
        if (activeSubscribers.add(subscriber)) {
            subscriberClasses[subscriber] = subscriber.javaClass
            rebuildAffectedCaches(subscriber.javaClass)
        }
    }

    @Synchronized
    fun unsubscribe(subscriber: Any) {
        if (activeSubscribers.remove(subscriber))
            subscriberClasses.remove(subscriber)?.let { rebuildAffectedCaches(it) }
    }

    @JvmStatic
    fun <T : Event> post(event: T) {
        invokers[event.javaClass]?.invoke(event)
    }

    @JvmStatic
    fun hasListeners(eventClass: Class<out Event>): Boolean = invokers.containsKey(eventClass)

    fun <T : Event> registerListener(
        subscriberClass: Class<*>,
        eventClass: Class<T>,
        priority: Int,
        ignoreCancelled: Boolean,
        handler: (T) -> Unit
    ) = registerListener(subscriberClass, eventClass, priority, ignoreCancelled, null, handler)

    @Synchronized
    fun <T : Event> registerListener(
        subscriberClass: Class<*>,
        eventClass: Class<T>,
        priority: Int,
        ignoreCancelled: Boolean,
        packetClass: Class<*>?,
        handler: (T) -> Unit
    ) {
        val name = subscriberClass.simpleName.ifEmpty { subscriberClass.name }
        val entry = ListenerEntry(subscriberClass, EventListener(priority, ignoreCancelled, name, packetClass, handler))

        val existing = listenerArrays[eventClass] ?: emptyArray()
        val newArray = (existing + entry).sortedByDescending { it.listener.priority }.toTypedArray()
        listenerArrays[eventClass] = newArray
        val next = HashMap(invokers)
        rebuildInvoker(next, eventClass, newArray)
        invokers = next
    }

    private fun rebuildAffectedCaches(changedClass: Class<*>) {
        val next = HashMap(invokers)
        for ((eventClass, listeners) in listenerArrays) {
            if (listeners.any { it.subscriber == changedClass }) rebuildInvoker(next, eventClass, listeners)
        }
        invokers = next
    }

    private fun rebuildInvoker(
        into: HashMap<Class<out Event>, Invoker>,
        eventClass: Class<out Event>,
        allListeners: Array<ListenerEntry<*>>
    ) {
        val activeClasses = subscriberClasses.values

        @Suppress("UNCHECKED_CAST")
        val activeListeners = allListeners
            .filter { it.subscriber in activeClasses }
            .map { it.listener as EventListener<Event> }
            .toTypedArray()

        val profilerName = "ADM: ${eventClass.simpleName}"
        when {
            activeListeners.isEmpty() -> into.remove(eventClass)
            PacketEvent::class.java.isAssignableFrom(eventClass) -> into[eventClass] = PacketInvoker(profilerName, activeListeners)
            else -> into[eventClass] = InvokerFactory.build(profilerName, activeListeners)
        }
    }

    data class ListenerEntry<T : Event>(
        val subscriber: Class<*>,
        val listener: EventListener<T>
    )

    class EventListener<T : Event>(
        val priority: Int,
        ignoreCancelled: Boolean,
        val subscriberName: String,
        @JvmField val packetClass: Class<*>?,
        val handler: (T) -> Unit
    ) {
        @JvmField
        val checkCancelled = ignoreCancelled

        fun invoke(event: T) {
            if (!checkCancelled || event !is CancellableEvent || !event.isCancelled)
                handler(event)
        }
    }

    interface Invoker {
        fun invoke(event: Event)
    }

    private object EmptyInvoker : Invoker {
        override fun invoke(event: Event) {}
    }

    // Each packet class runs only its own listeners and the ones taking any packet, in the same order.
    private class PacketInvoker(private val profilerName: String, private val listeners: Array<EventListener<Event>>) : Invoker {
        private val byClass = ConcurrentHashMap<Class<*>, Invoker>()

        override fun invoke(event: Event) {
            val packetClass = (event as PacketEvent).packet.javaClass
            (byClass[packetClass] ?: resolve(packetClass)).invoke(event)
        }

        private fun resolve(packetClass: Class<*>): Invoker {
            val matching = listeners.filter { it.packetClass == null || it.packetClass.isAssignableFrom(packetClass) }.toTypedArray()
            val invoker = if (matching.isEmpty()) EmptyInvoker else InvokerFactory.build(profilerName, matching)
            byClass[packetClass] = invoker
            return invoker
        }
    }

    private object InvokerFactory {
        fun build(profilerName: String, listeners: Array<EventListener<Event>>): Invoker {
            return object : Invoker {
                override fun invoke(event: Event) {
                    val profiler = Profiler.get()
                    profiler.push(profilerName)
                    try {
                        for (listener in listeners) {
                            profiler.push(listener.subscriberName)
                            try {
                                listener.invoke(event)
                            } catch (t: Throwable) {
                                logError(t, "${listener.subscriberName} (${event.javaClass.simpleName})")
                            } finally {
                                profiler.pop()
                            }
                        }
                    } finally {
                        profiler.pop()
                    }
                }
            }
        }
    }
}

inline fun <reified T : Event> Any.on(
    priority: Int = 0,
    ignoreCancelled: Boolean = false,
    noinline handler: T.() -> Unit
) = EventBus.registerListener(this.javaClass, T::class.java, priority, ignoreCancelled) {
    it.handler()
}

inline fun <reified P : Packet<*>> Any.onReceive(
    priority: Int = 0,
    ignoreCancelled: Boolean = false,
    noinline handler: P.(PacketEvent.Receive) -> Unit
) = EventBus.registerListener(this.javaClass, PacketEvent.Receive::class.java, priority, ignoreCancelled, P::class.java) {
    (it.packet as P).handler(it)
}

inline fun <reified P : Packet<*>> Any.onSend(
    priority: Int = 0,
    ignoreCancelled: Boolean = false,
    noinline handler: P.(PacketEvent.Send) -> Unit
) = EventBus.registerListener(this.javaClass, PacketEvent.Send::class.java, priority, ignoreCancelled, P::class.java) {
    (it.packet as P).handler(it)
}
