package com.devgineerclient.splits

/**
 * How good each boss sub split was, as a colour: six bands cut from real F7 runs, gold for a
 * personal best, light gray for a filler step that never varies.
 *
 *     §2 dark green   §a green   §e yellow   §c red   §4 dark red   §0 black
 *
 * Every step is graded on the clock its own mechanics run on: the server's ticks, except the
 * terminal sections (players, so lag is part of it), which are graded on real time.
 *
 * Nothing here touches Minecraft, so it tests headlessly.
 */
object SubSplitGrades {

    enum class Clock { TICKS, REAL }

    /**
     * One step's bands: [limits] are the five upper bounds (inclusive) of dark green, green, yellow,
     * red and dark red, in ticks or milliseconds; slower is black. Null limits mark a filler step.
     * [floor] is the fastest the step can really be done; anything faster is a missed moment, not a time,
     * and never becomes a best.
     *
     * [fromStart]: graded on the ticks since the split started rather than the step's own length
     * (Maxor's Lure: what matters is the tick his first hit lands on, not how long after the laser
     * line). [lateAfter]: a filler step that turns dark red once it ends this many ticks after the
     * split started (Necron's locks, when ARGH missed its first grid tick).
     */
    class Bands(val clock: Clock, val limits: LongArray?, val floor: Long, val fromStart: Boolean = false, val lateAfter: Long? = null)

    /**
     * Color Based Off Time (Odin's Splits, read by [PaceTargets]): off, nothing is graded - every
     * time is drawn in its split's own colour.
     */
    @JvmStatic var byTime: () -> Boolean = { true }

    /** Show PB (Odin's Splits, read by [PaceTargets]): off, a best is not picked out in gold, just graded like any other time. */
    @JvmStatic var showBest: () -> Boolean = { false }

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
     * Per step id (SubSplitTracker's), F7. Fixed numbers, hard-coded and the same for everyone; not
     * recomputed from anything the client sees. Change them here.
     *
     * Cut from 500 F7 runs since Hypixel's boss update (Oct 2026), each step timed the way
     * SubSplitTracker / SplitTracker time it; a run recorded by several of its party counts once,
     * and an uncleared run counts for the steps it got through.
     *
     * Continuous steps: floor is the fastest seen, then roughly the runs' p5 / p25 / p50 / p75 / p90.
     * Steps locked to a check grid are graded by checks missed instead, since a percentile cut
     * would split identical outcomes: the whole on-time step is dark green, the tick or two it
     * jitters by included, then each missed check one colour down (yellow, red, dark red, black) -
     * [stepped].
     */
    val BANDS: Map<String, Bands> = mapOf(
        // First line to "handle this": 230-689.
        "watcher.dialogue" to ticks(230, 261, 290, 303, 322, 344),
        // "handle this" to "proven yourself": the camp, his mobs coming out and dying, 559-2530.
        "watcher.fight" to ticks(559, 616, 674, 712, 752, 804),

        // 10-tick checks: both crystals placed on the s0+81 check make "charging up" at 107-110
        // (about half of runs), then 115-120, 127-131, 138...
        "maxor.crystals" to stepped(Clock.TICKS, 107, 110, 10),
        // The first hit's tick (from his first line): 124-127 on time, 129-131 the next chance, then
        // every 10 ticks (139-141, 149-151, 159-161).
        "maxor.lure" to Bands(Clock.TICKS, longArrayOf(127, 131, 141, 151, 161), 124, fromStart = true),
        // Crystals back, placed and charged again: 74-86 on time, then a 10-tick check later
        // (89-91, 99-101, 110...).
        "maxor.cooldown" to stepped(Clock.TICKS, 74, 86, 10),
        // Second hit to the beacon: 0-655.
        "maxor.kill" to ticks(0, 1, 3, 4, 7, 12),
        // 60-63 almost always.
        "maxor.animation" to filler(),

        // 646-649 almost always.
        "storm.opening" to filler(),
        // 20-tick crush checks: 10-13 is the first one.
        "storm.crush1" to stepped(Clock.TICKS, 10, 13, 20),
        // Crush to enraged: 0-319.
        "storm.pin" to ticks(0, 1, 2, 5, 16, 27),
        // Enraged to reaching Yellow: 78-2123 when he flies the whole way; a run where he is crushed
        // near Yellow (0-24) is under the floor, so never a best.
        "storm.flight" to ticks(78, 86, 90, 94, 102, 161),
        // The first check after he reaches Yellow is up to 20 ticks away.
        "storm.crush2" to stepped(Clock.TICKS, 0, 20, 20),
        // Second crush to his death line: 0-763.
        "storm.kill" to ticks(0, 2, 3, 6, 12, 23),
        // 59-63 almost always.
        "storm.animation" to filler(),

        // Real time. S1 6.95 s up, S2 5.10, S3 5.30, S4 3.65. S1's dark red is hand-picked: its p90
        // (36 s) is the runs that waited on someone.
        "terms.s1" to real(6950, 7450, 8600, 9800, 13550, 17200),
        "terms.s2" to real(5100, 7100, 9100, 11700, 14600, 19000),
        "terms.s3" to real(5300, 6600, 8750, 10750, 13350, 16900),
        "terms.s4" to real(3650, 4350, 6650, 8000, 10100, 14450),

        // The core opening to everyone in it: 0-80 (a 0 is under the floor).
        "goldor.leaps" to ticks(7, 9, 15, 21, 29, 43),
        // Everyone in to Necron's first line (his death's fixed ticks included): 73-258.
        "goldor.kill" to ticks(73, 80, 94, 109, 130, 148),

        // Necron's fight: intro to the sidestep 81-84 ticks; the trip back 9-216.
        "necron.intro" to filler(),
        "necron.trip1" to ticks(9, 11, 13, 16, 19, 26),
        // ARGH! is said 269-273 ticks into the fight.
        "necron.lock1" to Bands(Clock.TICKS, null, 0, lateAfter = 275),
        // ARGH! to the run's end (EXTRA STATS; F7 has no end animation): 139-155 on time - he dies
        // on a 20-tick check, so 161-174 is one check late, 182-193 a second.
        "necron.kill" to stepped(Clock.TICKS, 139, 155, 20),

        // The run's own splits (Odin's), F7, all but the terminals in ticks.
        // Open: Mort's line to the blood door. Hand-picked limits: 8 / 14 / 19 / 25 / 32 s;
        // the floor (5 s) only keeps a missed moment from standing as a best.
        "split.open" to ticks(100, 160, 280, 380, 500, 640),
        // Blood (camp): the Watcher's first line to "proven yourself", 907-1370.
        // The limits from here down are hand-picked, not percentiles.
        "split.blood" to ticks(907, 920, 1000, 1100, 1200, 1400),
        // Portal: "proven yourself" to Maxor's line, mostly the warp: usually 4.10 or 4.60 s,
        // 3.1-4.0 when it opened early, over 9 s when someone was late.
        "split.portal" to ticks(40, 76, 88, 100, 120, 160),
        // Enter: Odin's Boss Entry, Mort to Maxor's line (Open + Blood + Portal): ~60-160 s, median ~71.
        "split.enter" to ticks(1100, 1240, 1400, 1560, 1700, 1900),
        // Maxor 263-1498, Storm 820-1624: a perfect fight is ~263-277 and ~820-829.
        "split.maxor" to ticks(263, 272, 280, 300, 340, 400),
        "split.storm" to ticks(820, 824, 840, 870, 960, 1080),
        // Goldor's line to "The Core entrance is opening!": ~34-92 s. The floor is below the
        // fastest seen so a faster run than any yet can still set a best.
        "split.terms" to real(25000, 28000, 31000, 37000, 41000, 48000),
        // The core opening to Necron's line: 96-269.
        "split.goldor" to ticks(96, 100, 126, 140, 160, 180),
        // Necron's first line to the run's end (EXTRA STATS): 409-425 on time, 430-440 one
        // 20-tick check late, 541-714 later.
        "split.necron" to ticks(409, 425, 436, 447, 490, 540),
    )

    /**
     * M7's own, where its fight differs (cut from 55 M7 runs the same way); every other step is
     * graded on F7's bands.
     */
    val BANDS_M7: Map<String, Bands> = mapOf(
        // Crush to enraged: 4-54.
        "storm.pin" to ticks(4, 5, 8, 9, 13, 16),
        // Enraged to reaching Yellow: 83-126 when he flies the whole way.
        "storm.flight" to ticks(78, 83, 87, 90, 92, 102),
        // Second crush to his death line: 4-62.
        "storm.kill" to ticks(4, 5, 7, 10, 17, 26),
        // Real time. S1 7.60 s up, S2 5.70, S3 6.50, S4 4.20.
        "terms.s1" to real(7600, 8150, 8450, 8950, 9900, 12150),
        "terms.s2" to real(5700, 6500, 8400, 9950, 11800, 14900),
        "terms.s3" to real(6500, 6950, 8450, 10200, 12350, 14350),
        "terms.s4" to real(4200, 5050, 6600, 8000, 9400, 10750),
        // Everyone in to Necron's first line: 118-187.
        "goldor.kill" to ticks(118, 123, 134, 139, 147, 157),
        // The trip back to mid: 18-213.
        "necron.trip1" to ticks(18, 19, 24, 26, 32, 41),
        // ARGH! to the TNT burst he dies in, on a 20-tick check: 99-104, then 118-127, 139-149,
        // 159-170, 180-190 (753 M7 runs: p25 121, median 141, p75 162). A band per check.
        "necron.kill" to Bands(Clock.TICKS, longArrayOf(106, 128, 150, 172, 192), 99),
        // His death to the Wither King's first line (every relic placed, plus the 64-69 ticks he takes
        // to say so), 753 M7 runs: 89 up, p5 101, p25 111, median 121, p75 150, p90 212.
        "necron.relics" to ticks(89, 101, 111, 121, 150, 212),
        // Storm 822-969, the core opening to Necron's line 134-210.
        "split.storm" to ticks(822, 826, 829, 832, 857, 887),
        "split.goldor" to ticks(134, 144, 153, 157, 166, 180),
        // Necron's first line to the Wither King's (his kill and the relics), 753 M7 runs: 457 up,
        // p5 482, p25 510, median 533, p75 570, p90 633.
        "split.necron" to ticks(457, 482, 510, 533, 570, 633),
        // The Wither King's first line to the run's end: 523 up, p5 533, p25 547, median 566, p75 591, p90 623.
        "split.dragons" to ticks(523, 533, 547, 566, 591, 623),
    )

    /** [id]'s bands on [floor] (F7's where M7 has none of its own); null off F7 and M7. */
    fun bands(id: String, floor: String?): Bands? = when (floor) {
        "M7" -> BANDS_M7[id] ?: BANDS[id]
        "F7" -> BANDS[id]
        else -> null
    }

    /** Odin's split names (colour codes stripped) to their ids here. */
    val MAIN_SPLITS = mapOf(
        "Blood Open" to "split.open", "Blood Clear" to "split.blood", "Portal Entry" to "split.portal", "Boss Entry" to "split.enter", "Maxor" to "split.maxor", "Storm" to "split.storm",
        "Terminals" to "split.terms", "Goldor" to "split.goldor", "Necron" to "split.necron",
        // M7's Dragons (Odin's last floor 7 split; F7 never starts it).
        "Cleared" to "split.dragons",
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
     * The colour for [value] of step [id] on [floor]. A finished step at or under [best] is gold (a
     * best ties count: it is the run that set it). Off F7 and M7, where no bands were measured, only
     * the gold and the filler gray apply, and [fallback] (the step's own colour) the rest.
     */
    fun colour(id: String, value: Long, finished: Boolean, best: Long?, floor: String?, fallback: String): String {
        if (!byTime()) return fallback
        val banded = floor == "F7" || floor == "M7"
        val b = bands(id, floor) ?: BANDS[id] ?: BANDS_M7[id] ?: return fallback
        b.lateAfter?.let { return if (finished && value > it) COLOURS[4] else FILLER }
        val limits = b.limits ?: return FILLER
        if (showBest() && finished && best != null && value <= best && value >= b.floor) return BEST
        if (!banded) return fallback
        val i = limits.indexOfFirst { value <= it }
        return COLOURS[if (i < 0) COLOURS.lastIndex else i]
    }

    /** Whether a finished [value] can stand as a best on [floor]: banded (not filler) and not under the floor. */
    fun canBeBest(id: String, value: Long, floor: String? = "F7"): Boolean {
        val b = bands(id, floor) ?: BANDS[id] ?: BANDS_M7[id] ?: return false
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
