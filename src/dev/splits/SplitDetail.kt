package com.devgineerclient.splits


/**
 * The moments inside a split, filed under the split's label ([SplitTracker]'s constants).
 *
 * A [step] is one of the split's own milestones — the Watcher's lines, the portal opening — and
 * shows at every level. Everything else is extra detail that only Debug shows.
 */
class SplitDetail {

    /** [note] says how it was found (Debug shows it), e.g. "chat" or "seen 12 blocks away". */
    data class Entry(val label: String, val at: Stamp, val who: String = "", val step: Boolean = false, val note: String = "")

    private val entries = linkedMapOf<String, MutableList<Entry>>()

    fun reset() = entries.clear()

    fun add(split: String, at: Stamp, label: String, who: String = "", step: Boolean = false, note: String = "") {
        add(split, Entry(label, at, who, step, note))
    }

    fun add(split: String, e: Entry) {
        entries.getOrPut(split) { mutableListOf() } += e
    }

    /** Puts [new] where [old] (that very entry) is; nothing when it is gone. */
    fun replace(split: String, old: Entry, new: Entry) {
        val l = entries[split] ?: return
        val i = l.indexOfFirst { it === old }
        if (i >= 0) l[i] = new
    }

    fun lines(split: String): List<Entry> = entries[split].orEmpty()
}
