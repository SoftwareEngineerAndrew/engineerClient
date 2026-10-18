package com.engineerclient.p3sim.clear.state

/**
 * Things that happen at fixed run-clock ticks (`Run.t`), fired in order as the clock passes them. Keeping the schedule in the
 * run clock (not in absolute server ticks) is what lets a savepoint restore replay it: the clock is put back and so is
 * [Fired]'s last tick. Pure; no Minecraft.
 */
class TickSchedule<T> {
    private class Entry<T>(val at: Int, val seq: Int, val item: T)

    private val entries = ArrayList<Entry<T>>()
    private var seq = 0

    fun add(at: Int, item: T) { entries += Entry(at, seq++, item) }

    val size: Int get() = entries.size

    /** The items due in `(previous, now]`, earliest first (insertion order within one tick). */
    fun due(previous: Int, now: Int): List<T> =
        entries.filter { it.at > previous && it.at <= now }.sortedWith(compareBy({ it.at }, { it.seq })).map { it.item }

    /** The tick an item was scheduled for, or null. */
    fun timeOf(match: (T) -> Boolean): Int? = entries.firstOrNull { match(it.item) }?.at

    fun clear() { entries.clear(); seq = 0 }
}
