package com.engineerclient.p3sim.clear.ui

import com.engineerclient.p3sim.pack.layouts.Layout
import com.engineerclient.p3sim.pack.layouts.LayoutPack

/** One line of the layout browser: what a recorded layout is, in the words a player picks by. */
class LayoutRow(
    val id: String,
    val rooms: Int,
    val puzzles: List<String>,
    val secrets: Int,
    /** How long the recorded run took from Mort's first line to its last recorded tick, in seconds; null when unknown. */
    val seconds: Int?,
    /** In the default pool (`/ads run random` can pick it). A layout that is not may not build or may play oddly. */
    val inPool: Boolean,
    val party: List<String>,
    /** Lower-case text the search matches: the id, the puzzle and room names. */
    val haystack: String,
) {
    /** "2026-09-23 12:46" for an id that starts with a date and time, else the id. */
    val title: String get() = LayoutBrowserModel.title(id)
    val time: String get() = seconds?.let { LayoutBrowserModel.clock(it) } ?: "?"

    /** The one line on the button. */
    fun line(): String = "$title  $rooms rooms  ${puzzles.size} puzzles  $secrets secrets  $time" + if (inPool) "" else "  (not complete)"
}

/** The layout browser's data and behaviour, without any screen: building rows from the pack, searching, sorting, paging. Pure. */
object LayoutBrowserModel {
    enum class Sort(val label: String, val about: String) {
        NEWEST("Newest", "The most recently recorded first."),
        FASTEST("Fastest", "The shortest recorded run first (runs with no recorded time last)."),
        PUZZLES("Puzzles", "The most puzzles first."),
        SECRETS("Secrets", "The most secrets first.");

        fun next(): Sort = entries[(ordinal + 1) % entries.size]
    }

    fun rows(pack: LayoutPack): List<LayoutRow> = pack.layouts.map(::row)

    fun row(l: Layout): LayoutRow {
        val names = l.rooms.map { it.name }
        return LayoutRow(
            id = l.id, rooms = l.rooms.size, puzzles = l.puzzles, secrets = l.secretsTotal,
            seconds = l.endTick?.takeIf { it > 0 }?.let { it / 20 }, inPool = l.inDefaultPool, party = l.party,
            haystack = (listOf(l.id) + l.puzzles + names).joinToString(" ").lowercase(),
        )
    }

    /** The rows that match [query] (every word must appear in the id, a puzzle or a room name), in [sort] order. [poolOnly] hides incomplete layouts. */
    fun view(rows: List<LayoutRow>, query: String, sort: Sort, poolOnly: Boolean): List<LayoutRow> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kept = rows.filter { r -> (!poolOnly || r.inPool) && words.all { r.haystack.contains(it) } }
        return when (sort) {
            Sort.NEWEST -> kept.sortedByDescending { it.id }
            Sort.FASTEST -> kept.sortedWith(compareBy<LayoutRow> { it.seconds == null }.thenBy { it.seconds ?: 0 }.thenByDescending { it.id })
            Sort.PUZZLES -> kept.sortedWith(compareByDescending<LayoutRow> { it.puzzles.size }.thenByDescending { it.id })
            Sort.SECRETS -> kept.sortedWith(compareByDescending<LayoutRow> { it.secrets }.thenByDescending { it.id })
        }
    }

    /** Page count for [total] rows at [pageSize] per page (at least 1). */
    fun pageCount(total: Int, pageSize: Int): Int = maxOf(1, (total + pageSize - 1) / pageSize)

    /** Rows of page [page] (0-based, clamped into range). */
    fun page(rows: List<LayoutRow>, page: Int, pageSize: Int): List<LayoutRow> {
        val p = page.coerceIn(0, pageCount(rows.size, pageSize) - 1)
        return rows.drop(p * pageSize).take(pageSize)
    }

    sealed interface Resolved {
        class One(val id: String) : Resolved
        class Many(val ids: List<String>) : Resolved
        data object Nothing : Resolved
    }

    /**
     * A layout id typed by hand: an exact id, else the one id that starts with the text, else the one that contains it.
     * Several matches are reported so the player can be asked to be more exact.
     */
    fun resolve(ids: List<String>, text: String): Resolved {
        if (text in ids) return Resolved.One(text)
        val lower = text.lowercase()
        for (match in listOf<(String) -> Boolean>({ it.lowercase().startsWith(lower) }, { it.lowercase().contains(lower) })) {
            val hits = ids.filter(match)
            if (hits.size == 1) return Resolved.One(hits[0])
            if (hits.size > 1) return Resolved.Many(hits)
        }
        return Resolved.Nothing
    }

    /** "2026-09-23_12-46-33_F7" -> "2026-09-23 12:46"; anything else unchanged. */
    fun title(id: String): String {
        val m = Regex("^(\\d{4}-\\d{2}-\\d{2})_(\\d{2})-(\\d{2})-\\d{2}").find(id) ?: return id
        return "${m.groupValues[1]} ${m.groupValues[2]}:${m.groupValues[3]}"
    }

    /** Seconds as "5:12" (or "1:05:12" past an hour). */
    fun clock(seconds: Int): String {
        val h = seconds / 3600; val m = seconds / 60 % 60; val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
