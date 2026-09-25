package com.engineerclient.splits

/**
 * The moments inside a split, filed under the split's label ([SplitTracker]'s constants).
 *
 * A [step] is one of the split's own milestones — the Watcher's lines, the portal opening — and
 * shows at every level. Everything else is extra detail that only Extreme shows.
 */
class SplitDetail {

    data class Entry(val label: String, val at: Stamp, val who: String = "", val step: Boolean = false)

    private val entries = linkedMapOf<String, MutableList<Entry>>()

    fun reset() = entries.clear()

    fun add(split: String, at: Stamp, label: String, who: String = "", step: Boolean = false) {
        entries.getOrPut(split) { mutableListOf() } += Entry(label, at, who, step)
    }

    fun lines(split: String): List<Entry> = entries[split].orEmpty()
}
