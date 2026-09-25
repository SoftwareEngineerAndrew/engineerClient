package com.engineerclient.splits

/**
 * Everything known about a split, rather than just its 25-step breakdown.
 *
 * The sub-split HUDs normally show the boss's named steps ([SubSplitTracker]). Turn "Sub Split
 * Detail" to Everything and they show these instead: every moment of the split that anything in the
 * game told us about, timed from the split's start. It is deliberately noisy — the point is to see
 * what is there before deciding what is worth keeping.
 *
 * Each entry is a [Stamp] and a line. A detail source files entries under a split's label, the same
 * labels [SplitTracker] uses, and the module renders them in split order.
 */
class SplitDetail {

    private val entries = linkedMapOf<String, MutableList<SubSplit>>()

    fun reset() = entries.clear()

    /** Files a line under a split. Repeated identical lines at the same moment are dropped. */
    fun add(split: String, at: Stamp, line: String) {
        val list = entries.getOrPut(split) { mutableListOf() }
        if (list.lastOrNull()?.let { it.at == at && it.label == line } == true) return
        list += SubSplit(line, at)
    }

    fun lines(split: String): List<SubSplit> = entries[split].orEmpty()

    fun has(split: String): Boolean = !entries[split].isNullOrEmpty()

    /**
     * An average line for a repeated stat, the way the blood run wants one per room: "Door 1.80s
     * avg (5)". Returns null when there is nothing to average.
     */
    fun average(values: List<Long>): String? {
        if (values.isEmpty()) return null
        return SplitFormat.time(values.sum() / values.size, true) + " avg (${values.size})"
    }
}
