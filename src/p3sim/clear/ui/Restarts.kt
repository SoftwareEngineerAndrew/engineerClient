package com.engineerclient.p3sim.clear.ui

import com.engineerclient.p3sim.run.Savepoint

/**
 * Which savepoint `/ads run restart` and `/ads run restart room` go back to, and how the list reads in chat. Pure.
 * (The engine takes the savepoints: "Start" at the end of `Run.begin`, one on entering each room, and manual ones.)
 */
object Restarts {
    /** A savepoint to restore, with an optional plain sentence explaining a fallback; or nothing to restore, with the reason. */
    sealed interface Choice {
        class Use(val savepoint: Savepoint, val note: String? = null) : Choice
        class None(val message: String) : Choice
    }

    const val START_LABEL = "Start"

    /** The run's beginning: the savepoint labelled "Start" (the oldest one if none carries the label). */
    fun start(all: List<Savepoint>): Choice {
        val sp = all.firstOrNull { it.label == START_LABEL } ?: all.firstOrNull()
            ?: return Choice.None("There are no savepoints yet, so there is nothing to go back to.")
        return Choice.Use(sp)
    }

    /**
     * The savepoint taken when the party entered [roomIndex] (the latest if it was entered more than once). With no room
     * (standing in a doorway) or a room that has none, the most recent room savepoint; failing that, the start.
     */
    fun room(all: List<Savepoint>, roomIndex: Int?): Choice {
        if (all.isEmpty()) return Choice.None("There are no savepoints yet, so there is nothing to go back to.")
        if (roomIndex != null) all.lastOrNull { it.roomIndex == roomIndex }?.let { return Choice.Use(it) }
        val recent = all.lastOrNull { it.roomIndex != null }
        if (recent != null) return Choice.Use(recent, "You are not in a room with a savepoint, so this goes back to the latest one: ${recent.label}.")
        val first = start(all)
        return if (first is Choice.Use) Choice.Use(first.savepoint, "No room has a savepoint yet, so this goes back to the start.") else first
    }

    /** "1  Start  -5.0 s", one line per savepoint, numbered from 1 in the order given (oldest first). */
    fun lines(all: List<Savepoint>): List<String> =
        all.mapIndexed { i, sp -> "${i + 1}. ${sp.label} (${clock(sp.t)})" }

    /** A savepoint by its 1-based number or its id; null when neither matches. */
    fun find(all: List<Savepoint>, word: String): Savepoint? =
        word.toIntOrNull()?.let { all.getOrNull(it - 1) } ?: all.firstOrNull { it.id == word }

    /** The run clock (ticks, negative in the countdown) as seconds with a tenth: "-5.0 s", "62.5 s". */
    fun clock(ticks: Int): String {
        val tenths = Math.round(ticks / 2.0).toInt()
        val sign = if (tenths < 0) "-" else ""
        val a = Math.abs(tenths)
        return "$sign${a / 10}.${a % 10} s"
    }
}
