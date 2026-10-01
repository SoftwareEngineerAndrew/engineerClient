package com.engineerclient.splits

/**
 * How good each boss sub split was, as a colour: six bands cut from the recorded F7 runs, gold for
 * a personal best, light gray for a filler step that never varies (docs/mechanics/sub-splits.md).
 *
 *     §2 dark green   §a green   §e yellow   §c red   §4 dark red   §0 black
 *
 * Every step is graded on the clock its own mechanics run on: the server's ticks, except Maxor's
 * laser cooldown (10 s of real time) and the terminal sections (players, so lag is part of it),
 * which are graded on real time.
 *
 * Nothing here touches Minecraft, so it tests headlessly.
 */
object SubSplitGrades {

    enum class Clock { TICKS, REAL }

    /**
     * One step's bands: [limits] are the five upper bounds (inclusive) of dark green, green, yellow,
     * red and dark red, in ticks or milliseconds; slower is black. Null limits mark a filler step.
     * [floor] is the fastest the recordings allow; anything faster is a missed moment, not a time,
     * and never becomes a best.
     */
    class Bands(val clock: Clock, val limits: LongArray?, val floor: Long)

    val COLOURS = listOf("§2", "§a", "§e", "§c", "§4", "§0")
    const val BEST = "§6"
    const val FILLER = "§7"

    private fun ticks(floor: Long, vararg l: Long) = Bands(Clock.TICKS, l, floor)
    private fun real(floor: Long, vararg l: Long) = Bands(Clock.REAL, l, floor)
    private fun filler(floor: Long) = Bands(Clock.TICKS, null, floor)

    /** Per step id (SubSplitTracker's). The cuts are the recordings' p10 / p25 / p50 / p75 / p90. */
    val BANDS: Map<String, Bands> = mapOf(
        // Filled from the recorded runs (tools/boss-mechanics/subsplits.py).
    )

    /** The value a step is graded on: ticks, or milliseconds of real time. */
    fun value(id: String, realMs: Long, ticks: Long): Long =
        if (BANDS[id]?.clock == Clock.REAL) realMs else ticks

    fun clock(id: String): Clock = BANDS[id]?.clock ?: Clock.TICKS

    /**
     * The colour for [value] of step [id]. A finished step at or under [best] is gold (a best ties
     * count: it is the run that set it). [banded] is false off F7, where the bands weren't measured:
     * then only the gold and the filler gray apply, and [fallback] (the step's own colour) the rest.
     */
    fun colour(id: String, value: Long, finished: Boolean, best: Long?, banded: Boolean, fallback: String): String {
        val b = BANDS[id] ?: return fallback
        val limits = b.limits ?: return FILLER
        if (finished && best != null && value <= best && value >= b.floor) return BEST
        if (!banded) return fallback
        val i = limits.indexOfFirst { value <= it }
        return COLOURS[if (i < 0) COLOURS.lastIndex else i]
    }

    /** Whether a finished [value] can stand as a best: banded (not filler) and not under the floor. */
    fun canBeBest(id: String, value: Long): Boolean {
        val b = BANDS[id] ?: return false
        return b.limits != null && value >= b.floor
    }

    /** Bests as stored in the config: `id=value` pairs, comma separated. */
    fun parseBests(text: String): MutableMap<String, Long> =
        text.split(',').mapNotNull { e ->
            val (k, v) = e.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            v.trim().toLongOrNull()?.let { k.trim() to it }
        }.toMap(HashMap())

    fun formatBests(bests: Map<String, Long>): String = bests.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
}
