package com.engineerclient.splits

/**
 * How good each boss sub split was, as a colour: six bands cut from the recorded F7 runs, gold for
 * a personal best, light gray for a filler step that never varies (docs/mechanics/sub-splits.md).
 *
 *     §2 dark green   §a green   §e yellow   §c red   §4 dark red   §0 black
 *
 * Every step is graded on the clock its own mechanics run on: the server's ticks, except the
 * terminal sections (players, so lag is part of it), which are graded on real time. (Maxor's
 * cooldown was 10 s of real time until Hypixel's boss update of 5 Oct 2026; now it is ticks too.)
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

    /**
     * Color Based Off Time (Odin's Splits, set by OdinSplitsLook): off, nothing is graded - every
     * time is drawn in its split's own colour again, as before the bands.
     */
    @JvmStatic var byTime: () -> Boolean = { true }

    val COLOURS = listOf("§2", "§a", "§e", "§c", "§4", "§0")
    const val BEST = "§6"
    const val FILLER = "§7"

    private fun ticks(floor: Long, vararg l: Long) = Bands(Clock.TICKS, l, floor)
    private fun real(floor: Long, vararg l: Long) = Bands(Clock.REAL, l, floor)
    private fun filler() = Bands(Clock.TICKS, null, 0)

    /** A grid step: on time up to [onTime], then one colour down per [step] missed. */
    private fun stepped(clock: Clock, floor: Long, onTime: Long, step: Long, fromStart: Boolean = false) =
        Bands(clock, longArrayOf(onTime, onTime, onTime + step, onTime + 2 * step, onTime + 3 * step), floor, fromStart)

    /**
     * Per step id (SubSplitTracker's). Fixed numbers, hard-coded and the same for everyone; not
     * recomputed from anything the client sees. Change them here.
     *
     * Cut from every Better PF recording of F7 since Hypixel's boss update (5 Oct 2026, between
     * 20:39 and 21:04 UTC; recordings from 21:05 on): 166 recordings, 117 runs once the same run
     * recorded by several of its party is counted once (the recording with the most in it kept),
     * uncleared runs counted for the steps they got through. Each step is timed the way
     * SubSplitTracker / SplitTracker time it now, and n below is how many runs had that step.
     *
     * Continuous steps: floor is the fastest recorded, then the runs' p5 / p25 / p50 / p75 / p90.
     * Steps locked to a check grid are graded by checks missed instead, since a percentile cut
     * would split identical outcomes: the whole on-time step is dark green, the tick or two it
     * jitters by included, then each missed check one colour down (yellow, red, dark red, black) -
     * [stepped].
     */
    val BANDS: Map<String, Bands> = mapOf(
        // First line to "handle this": 229-516 (n 78).
        "watcher.dialogue" to ticks(229, 255, 294, 307, 324, 355),
        // "handle this" to the Watcher seen moving: 45-692 (n 55, the runs he was in view in; the
        // long tail is him coming into view already moving, not slow play, so dark red stops at 200
        // rather than its p90 of 455).
        "watcher.wait" to ticks(45, 63, 78, 114, 155, 200),
        // Not measurable as the tracker times them now, so the bands from before stand: the camp
        // has 12-14 named mobs since the update (73 of 78 runs), not 19, so the 19th player entity
        // that ends Camp is another room's mob, often before "handle this". The last named mob to
        // "proven yourself" was 0-337 (p5 1, p25 6, p50 10, p75 20, p90 49, n 75).
        "watcher.camp" to ticks(485, 520, 560, 600, 640, 670),
        "watcher.clear" to ticks(0, 2, 6, 10, 16, 25),

        // 10-tick checks: both crystals placed on the s0+81 check make "charging up" at 107-110
        // (40 of 75 runs; 107-559 in all), then 118-120, 129-130, 138...
        "maxor.crystals" to stepped(Clock.TICKS, 107, 110, 10),
        // The first hit's tick (from his first line): 124-128 on time (38 of 75; 124-752 in all).
        "maxor.lure" to stepped(Clock.TICKS, 124, 128, 10, fromStart = true),
        // Crystals back, placed and charged again: 83-85 on time (10 of 48; 83-580 in all), then a
        // 10-tick check later (91-105 the next cluster).
        "maxor.cooldown" to stepped(Clock.TICKS, 83, 85, 10),
        // Second hit to the beacon: 0-655 (n 47).
        "maxor.kill" to ticks(0, 1, 3, 5, 7, 17),
        // 61-63 in 71 of 74.
        "maxor.animation" to filler(),

        // 646-648 in 67 of 75.
        "storm.opening" to filler(),
        // 20-tick crush checks: 10-13 is the first one (49 of 74; 10-412 in all).
        "storm.crush1" to stepped(Clock.TICKS, 10, 13, 20),
        // Crush to enraged: 0-32 (n 69).
        "storm.pin" to ticks(0, 1, 2, 3, 6, 14),
        // Enraged to reaching Yellow: 78-478 in the 56 of 69 runs where he flew the whole way; the
        // other 13 (0-24) were crushed near Yellow and are under the floor, so never a best.
        "storm.flight" to ticks(78, 86, 90, 93, 99, 168),
        // The first check after he reaches Yellow is up to 20 ticks away: 0-20 in 41 of 61 (0-371).
        "storm.crush2" to stepped(Clock.TICKS, 0, 20, 20),
        // Second crush to his death line: 1-41 (n 61).
        "storm.kill" to ticks(1, 2, 3, 5, 18, 24),
        // 60-63 in 67 of 72.
        "storm.animation" to filler(),

        // Real time. S1 8.10-37.95 s (n 69), S2 6.75-38.75 s (n 64), S3 6.70-26.30 s (n 56),
        // S4 4.45-27.20 s (n 49).
        "terms.s1" to real(8100, 8700, 9500, 11000, 13200, 17200),
        "terms.s2" to real(6700, 7700, 9400, 12600, 16100, 21800),
        "terms.s3" to real(6650, 7800, 11400, 13000, 16700, 18800),
        "terms.s4" to real(4450, 5000, 7600, 8800, 12600, 25100),

        // The core opening to everyone in it: 0-80 (n 44; the one 0 is under the floor).
        "goldor.leaps" to ticks(7, 9, 15, 21, 29, 43),
        // Everyone in to Necron's first line (his death's fixed ticks included): 77-258 (n 44).
        "goldor.kill" to ticks(77, 85, 96, 108, 124, 134),

        // Necron's fight (n 26 that saw him move; 49 for the kill): intro to the sidestep 81-84
        // ticks; the trip back 11-37.
        "necron.intro" to filler(),
        "necron.trip1" to ticks(11, 12, 14, 16, 18, 26),
        // ARGH! is said 269-273 ticks into the fight in every run (26), back at 152-178 before it.
        "necron.lock1" to Bands(Clock.TICKS, null, 0, lateAfter = 275),
        // ARGH! to the run's end (EXTRA STATS; no end animation since the update): 140-155 on time
        // (36 of 49) - he dies on a 20-tick check, so 161-169 is one check late, 271-384 a second trip.
        "necron.kill" to stepped(Clock.TICKS, 140, 155, 20),

        // The run's own splits (Odin's), F7, all but the terminals in ticks.
        // Open: Mort's line to the blood door. The team's picks (7 Oct 2026): 8 / 14 / 19 / 25 / 32 s;
        // the floor (5 s) only keeps a missed moment from standing as a best.
        "split.open" to ticks(100, 160, 280, 380, 500, 640),
        // Blood: the Watcher's first line to "proven yourself", 907-1370 (n 75).
        "split.blood" to ticks(907, 936, 970, 1038, 1078, 1186),
        // Maxor 263-1498 (n 74), Storm 820-1624 (n 72): a perfect fight is ~263-277 and ~820-829.
        // The limits from here down are the team's own picks (7 Oct 2026), not percentiles.
        "split.maxor" to ticks(263, 265, 276, 290, 328, 384),
        "split.storm" to ticks(820, 822, 836, 850, 910, 1000),
        // Goldor's line to "The Core entrance is opening!": 34.00-91.80 s (n 49). The floor is below
        // the fastest recorded so a faster run than any yet can still set a best.
        "split.terms" to real(25000, 28000, 31000, 37000, 41000, 48000),
        // The core opening to Necron's line: 96-269 (n 49).
        "split.goldor" to ticks(96, 105, 119, 130, 149, 186),
        // Necron's first line to the run's end (EXTRA STATS): 409-425 on time (36 of 49), 430-440 one
        // 20-tick check late (10), 541-714 later (3).
        "split.necron" to ticks(409, 425, 436, 447, 490, 540),
    )

    /** Odin's split names (colour codes stripped) to their ids here. */
    val MAIN_SPLITS = mapOf(
        "Blood Open" to "split.open", "Blood Clear" to "split.blood", "Maxor" to "split.maxor", "Storm" to "split.storm",
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
        if (!byTime()) return fallback
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
