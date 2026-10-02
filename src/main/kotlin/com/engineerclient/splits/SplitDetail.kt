package com.engineerclient.splits

import com.engineerclient.recorder.EcRec

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
        entries.getOrPut(split) { mutableListOf() } += Entry(label, at, who, step, note)
        EcRec.line("ec.detail") { o -> o.str("split", split).str("label", label).at(at).str("who", who).bool("step", step).str("note", note) }
    }

    fun lines(split: String): List<Entry> = entries[split].orEmpty()
}
