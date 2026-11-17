package com.adm.config

internal class PackSelection(val selected: List<String>, val edit: String)

internal fun settlePackSelection(selected: List<String>, edit: String, available: List<String>): PackSelection {
    if (available.isEmpty()) return PackSelection(emptyList(), "")

    val kept = ArrayList<String>(selected.size + 1)
    for (raw in selected) {
        val id = raw.trim()
        if (id.isNotEmpty() && id in available && id !in kept) kept.add(id)
    }
    val editId = when {
        edit in available -> edit
        kept.isNotEmpty() -> kept[0]
        else -> available[0]
    }
    if (editId !in kept) kept.add(editId)
    return PackSelection(kept, editId)
}
