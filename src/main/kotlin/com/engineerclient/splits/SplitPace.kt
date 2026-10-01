package com.engineerclient.splits

/**
 * Pace and lag for the splits HUD and the scorecard (F7).
 *
 * Pace is the run's projected finish against the dark green times ([SubSplitGrades]' fastest
 * band): time already proven, plus dark green for everything still to come. Proven is every
 * finished split and sub split as it was, and for the one running, how far past its dark green
 * time it already is. So pace starts at a dark green run and only ever moves later, by exactly the
 * time lost so far. It is kept on both clocks: real time, and the server's ticks.
 *
 * Lag is the time the server lost against 20 ticks a second on the splits timed in ticks (all but
 * the blood rush and the terminals): their real time minus their ticks x 50 ms.
 *
 * Nothing here touches Minecraft, so it tests headlessly.
 */
object SplitPace {

    /** A time on both clocks. */
    data class Clocks(val ms: Long, val ticks: Long) {
        operator fun plus(o: Clocks) = Clocks(ms + o.ms, ticks + o.ticks)
    }

    /** The run's splits in order (SplitTracker's labels). */
    val ORDER = listOf(
        SplitTracker.OPEN, SplitTracker.BLOOD, SplitTracker.PORTAL, SplitTracker.MAXOR, SplitTracker.STORM,
        SplitTracker.TERMS, SplitTracker.GOLDOR, SplitTracker.NECRON, SplitTracker.ANIMATION,
    )

    /** Splits timed in real time: lag is not counted on them. */
    private val REAL_SPLITS = setOf(SplitTracker.OPEN, SplitTracker.TERMS)

    /**
     * Dark green per split. The six boss splits are their band's dark green limit; the blood rush,
     * the portal and the end animation have no bands and use the recorded runs' fastest 5%
     * (10.65 s, 73 ticks, and the animation's fixed 85).
     */
    private val SPLIT_REFS: Map<String, Clocks> = mapOf(
        SplitTracker.OPEN to real(10_650),
        SplitTracker.BLOOD to ticks(1200),
        SplitTracker.PORTAL to ticks(73),
        SplitTracker.MAXOR to ticks(511),
        SplitTracker.STORM to ticks(906),
        SplitTracker.TERMS to real(39_000),
        SplitTracker.GOLDOR to ticks(125),
        SplitTracker.NECRON to ticks(610),
        SplitTracker.ANIMATION to ticks(85),
    )

    /**
     * Dark green per sub split, as a length (SubSplitTracker's ids). The band's dark green limit,
     * except where a band is not a length: Maxor's Lure (graded on the tick of his first hit, 207,
     * after Crystals' 196) and Necron's locks (ARGH on its first grid tick, 330 and 545 into his
     * fight, after the steps before them). Fillers are their fixed length.
     */
    private val SUB_REFS: Map<String, Clocks> = mapOf(
        "watcher.dialogue" to ticks(415), "watcher.wait" to ticks(45), "watcher.camp" to ticks(680), "watcher.clear" to ticks(2),
        "maxor.crystals" to ticks(196), "maxor.lure" to ticks(11), "maxor.cooldown" to real(10_200), "maxor.kill" to ticks(1),
        "maxor.animation" to ticks(102),
        "storm.opening" to ticks(687), "storm.crush1" to ticks(13), "storm.pin" to ticks(1), "storm.flight" to ticks(86),
        "storm.crush2" to ticks(20), "storm.kill" to ticks(2), "storm.animation" to ticks(102),
        "terms.s1" to real(11_500), "terms.s2" to real(7_000), "terms.s3" to real(8_000), "terms.s4" to real(6_000),
        "goldor.leaps" to ticks(10), "goldor.kill" to ticks(100),
        "necron.intro" to ticks(159), "necron.trip1" to ticks(9), "necron.lock1" to ticks(162), "necron.space" to ticks(60),
        "necron.trip2" to ticks(1), "necron.lock2" to ticks(154), "necron.animation" to ticks(62),
    )

    private fun ticks(t: Long) = Clocks(t * 50, t)
    private fun real(ms: Long) = Clocks(ms, ms / 50)

    /** A sub split as the tracker has it: its id, and its times. */
    data class Sub(val id: String, val split: Split)

    /**
     * The projected finish. [splits] are the run's splits so far (SplitTracker's), [subs] a split's
     * sub splits so far.
     */
    fun pace(splits: List<Split>, subs: (String) -> List<Sub>, now: Stamp): Clocks {
        var total = Clocks(0, 0)
        for (label in ORDER) {
            val ref = SPLIT_REFS[label] ?: continue
            val split = splits.firstOrNull { it.label == label }
            total += when {
                split == null -> ref
                split.stop != null -> length(split, now)
                else -> running(split, ref, subs(label), now)
            }
        }
        return total
    }

    /**
     * The running split: its dark green, moved by what its sub splits have proven against theirs -
     * a finished one by however much faster or slower it was, the running one once it is past its
     * dark green. (The sub splits' dark greens don't add up to the split's exactly, so it is their
     * differences that count, not their sum.) Never less than the split's time so far.
     */
    private fun running(split: Split, ref: Clocks, subs: List<Sub>, now: Stamp): Clocks {
        var est = ref
        for (s in subs) {
            val subRef = SUB_REFS[s.id] ?: continue
            val len = length(s.split, now)
            val diff = Clocks(len.ms - subRef.ms, len.ticks - subRef.ticks)
            est += if (s.split.stop != null) diff else Clocks(maxOf(diff.ms, 0), maxOf(diff.ticks, 0))
        }
        return atLeast(est, length(split, now))
    }

    /** Lag: real time minus ticks x 50 ms over the tick-timed splits so far (never below 0). */
    fun lag(splits: List<Split>, now: Stamp): Long =
        splits.filter { it.label !in REAL_SPLITS }.sumOf { length(it, now).let { c -> c.ms - c.ticks * 50 } }.coerceAtLeast(0)

    private fun length(s: Split, now: Stamp): Clocks {
        val stop = s.stop ?: now
        return Clocks(stop.realMs - s.start.realMs, (stop.tick - s.start.tick).toLong())
    }

    private fun atLeast(a: Clocks, b: Clocks) = Clocks(maxOf(a.ms, b.ms), maxOf(a.ticks, b.ticks))

    /** m:ss, the way pace is shown. */
    fun mss(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}
