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
     *
     * [fromStart]: graded on the ticks since the split started rather than the step's own length
     * (Maxor's Lure: what matters is the tick his first hit lands on, not how long after the laser
     * line). [lateAfter]: a filler step that turns dark red once it ends this many ticks after the
     * split started (Necron's locks, when ARGH missed its first grid tick).
     */
    class Bands(val clock: Clock, val limits: LongArray?, val floor: Long, val fromStart: Boolean = false, val lateAfter: Long? = null)

    val COLOURS = listOf("§2", "§a", "§e", "§c", "§4", "§0")
    const val BEST = "§6"
    const val FILLER = "§7"

    private fun ticks(floor: Long, vararg l: Long) = Bands(Clock.TICKS, l, floor)
    private fun real(floor: Long, vararg l: Long) = Bands(Clock.REAL, l, floor)
    private fun filler() = Bands(Clock.TICKS, null, 0)

    /**
     * Per step id (SubSplitTracker's), from 173 five-player F7 runs (tools/boss-mechanics/subsplits.py).
     * Most cuts are the runs' p5 / p25 / p50 / p75 / p90. Steps locked to a check grid are graded by
     * checks missed instead (a percentile cut would split identical outcomes): on time is dark green
     * or green, then one, two and three or more missed checks are red, dark red and black.
     */
    val BANDS: Map<String, Bands> = mapOf(
        "watcher.dialogue" to ticks(385, 413, 443, 457, 478, 508),
        "watcher.wait" to ticks(44, 46, 65, 83, 102, 118),
        "watcher.camp" to ticks(645, 674, 725, 758, 806, 826),
        "watcher.clear" to ticks(0, 2, 7, 11, 17, 25),

        // 10-tick checks: both crystals placed on the s0+166 check make "charging up" at 194-196.
        "maxor.crystals" to ticks(192, 194, 196, 196, 206, 216),
        // The first hit's tick: 206 is the earliest there is.
        "maxor.lure" to Bands(Clock.TICKS, longArrayOf(205, 207, 207, 217, 227), 204, fromStart = true),
        // 10 s of real time between the hits.
        "maxor.cooldown" to real(9900, 10000, 10060, 10150, 10350, 11140),
        "maxor.kill" to ticks(0, 1, 3, 6, 9, 12),
        "maxor.animation" to filler(),

        "storm.opening" to filler(),
        // 20-tick crush checks: 11-13 is the t 699 check.
        "storm.crush1" to ticks(10, 11, 13, 13, 33, 53),
        "storm.pin" to ticks(0, 1, 3, 6, 16, 25),
        "storm.flight" to ticks(81, 86, 89, 92, 94, 100),
        // The first check after he reaches Yellow is up to 19 ticks away.
        "storm.crush2" to ticks(0, 2, 19, 19, 39, 59),
        "storm.kill" to ticks(0, 2, 4, 5, 10, 24),
        "storm.animation" to filler(),

        "terms.s1" to real(9600, 11450, 12650, 13750, 15800, 23450),
        "terms.s2" to real(5450, 7000, 9730, 12500, 15450, 19500),
        "terms.s3" to real(6550, 8150, 10850, 12800, 15850, 18600),
        "terms.s4" to real(4550, 6200, 8320, 10460, 13250, 18300),

        "goldor.leaps" to ticks(4, 11, 17, 20, 34, 62),
        // Includes the fixed 81-83 ticks from his death to Necron's line.
        "goldor.kill" to ticks(82, 98, 119, 138, 159, 179),

        "necron.intro" to filler(),
        "necron.trip1" to ticks(6, 9, 12, 16, 22, 28),
        // ARGH 1 on its first grid tick is said at 327-332 ticks into the fight.
        "necron.lock1" to Bands(Clock.TICKS, null, 0, lateAfter = 335),
        "necron.space" to ticks(56, 59, 63, 67, 72, 78),
        "necron.trip2" to ticks(1, 1, 3, 5, 8, 20),
        // ARGH 2 on its first grid tick: 543-547.
        "necron.lock2" to Bands(Clock.TICKS, null, 0, lateAfter = 550),
        "necron.animation" to filler(),

        // The run's own splits (Odin's), F7. Maxor's is graded in ticks like the rest, so its floor
        // stops a laggy run (fewer ticks for his 10 s of real-time cooldown) from setting a best.
        "split.blood" to ticks(1080, 1198, 1257, 1312, 1377, 1427),
        "split.maxor" to ticks(500, 508, 511, 518, 531, 604),
        "split.storm" to ticks(895, 903, 905, 924, 965, 1029),
        "split.terms" to real(33000, 38950, 44350, 50450, 59100, 71700),
        "split.goldor" to ticks(110, 124, 146, 166, 187, 213),
        // Necron's grid: 606-608 is every ARGH on time, then one and two 20-tick steps late.
        "split.necron" to ticks(599, 606, 608, 610, 628, 648),
    )

    /** Odin's split names (colour codes stripped) to their ids here. */
    val MAIN_SPLITS = mapOf(
        "Blood Clear" to "split.blood", "Maxor" to "split.maxor", "Storm" to "split.storm",
        "Terminals" to "split.terms", "Goldor" to "split.goldor", "Necron" to "split.necron",
    )

    /**
     * The value a step is graded on: ticks, or milliseconds of real time; for a step graded from its
     * split's start, [fromStartTicks].
     */
    fun value(id: String, realMs: Long, ticks: Long, fromStartTicks: Long = ticks): Long {
        val b = BANDS[id] ?: return ticks
        return when {
            b.clock == Clock.REAL -> realMs
            b.fromStart || b.lateAfter != null -> fromStartTicks
            else -> ticks
        }
    }

    fun clock(id: String): Clock = BANDS[id]?.clock ?: Clock.TICKS

    /**
     * The colour for [value] of step [id]. A finished step at or under [best] is gold (a best ties
     * count: it is the run that set it). [banded] is false off F7, where the bands weren't measured:
     * then only the gold and the filler gray apply, and [fallback] (the step's own colour) the rest.
     */
    fun colour(id: String, value: Long, finished: Boolean, best: Long?, banded: Boolean, fallback: String): String {
        val b = BANDS[id] ?: return fallback
        b.lateAfter?.let { return if (finished && value > it) COLOURS[4] else FILLER }
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
