package com.engineerclient.splits

/**
 * Pace and lag for the splits HUD and the scorecard (F7).
 *
 * Pace is the run's projected finish against a target per split: your Pace target box, else your PB
 * for it (OdinSplitsLook.paceTarget), else its dark green time ([SubSplitGrades]' fastest band).
 * Time already proven, plus the target for everything still to come. Proven is every finished
 * split as it was; the one running is its target, moved by what its finished sub splits gained or
 * lost against their share of that target, and by the running sub split once it is past its share.
 * So at load-in Pace is your PBs added up, and from there it moves by exactly the time gained or
 * lost. It is kept on both clocks: real time, and the server's ticks.
 *
 * Lag is the time the server lost against 20 ticks a second on the splits timed in ticks (all but
 * the terminals): their real time minus their ticks x 50 ms.
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
        SplitTracker.TERMS, SplitTracker.GOLDOR, SplitTracker.NECRON,
    )

    /** Splits timed in real time: lag is not counted on them. */
    private val REAL_SPLITS = setOf(SplitTracker.TERMS)

    /** Each split's id in SubSplitGrades (its bands, and the best kept for it). */
    val SPLIT_IDS = mapOf(
        SplitTracker.OPEN to "split.open", SplitTracker.BLOOD to "split.blood", SplitTracker.PORTAL to "split.portal",
        SplitTracker.MAXOR to "split.maxor", SplitTracker.STORM to "split.storm", SplitTracker.TERMS to "split.terms",
        SplitTracker.GOLDOR to "split.goldor", SplitTracker.NECRON to "split.necron",
    )

    /** Which sub splits (SubSplitTracker's ids, by prefix) make up each split. */
    private val SUB_PREFIX = mapOf(
        SplitTracker.BLOOD to "watcher.", SplitTracker.MAXOR to "maxor.", SplitTracker.STORM to "storm.",
        SplitTracker.TERMS to "terms.", SplitTracker.GOLDOR to "goldor.", SplitTracker.NECRON to "necron.",
    )

    /** Sub splits of a fixed length (fillers): their share of a split is always their own length. */
    private val FIXED_SUBS = setOf("maxor.animation", "storm.opening", "storm.animation", "necron.intro", "necron.lock1")

    /**
     * Dark green per split: what Pace counts a split as with no target and no PB. Each split with
     * bands is its band's dark green limit; the portal has none and uses the recorded runs' fastest
     * 5% (76 ticks of 74, F7 since Hypixel's boss update of 5 Oct 2026). There is no end animation
     * since the update: Necron runs to EXTRA STATS.
     */
    private val SPLIT_REFS: Map<String, Clocks> = mapOf(
        SplitTracker.OPEN to ticks(160),
        SplitTracker.BLOOD to ticks(936),
        SplitTracker.PORTAL to ticks(76),
        SplitTracker.MAXOR to ticks(265),
        SplitTracker.STORM to ticks(822),
        SplitTracker.TERMS to real(28_000),
        SplitTracker.GOLDOR to ticks(105),
        SplitTracker.NECRON to ticks(425),
    )

    /**
     * Dark green per sub split, as a length (SubSplitTracker's ids). The band's dark green limit,
     * except where a band is not a length: Maxor's Lure (graded on the tick of his first hit, 128,
     * after Crystals' 110) and Necron's lock (ARGH! on time, 270 into his fight, after the intro's
     * 82 and the trip's 12). Fillers are their fixed length (since Hypixel's boss update of 5 Oct
     * 2026: SubSplitGrades).
     */
    private val SUB_REFS: Map<String, Clocks> = mapOf(
        "watcher.dialogue" to ticks(255), "watcher.wait" to ticks(63), "watcher.camp" to ticks(520), "watcher.clear" to ticks(2),
        "maxor.crystals" to ticks(110), "maxor.lure" to ticks(18), "maxor.cooldown" to ticks(85), "maxor.kill" to ticks(1),
        "maxor.animation" to ticks(62),
        "storm.opening" to ticks(647), "storm.crush1" to ticks(13), "storm.pin" to ticks(1), "storm.flight" to ticks(86),
        "storm.crush2" to ticks(20), "storm.kill" to ticks(2), "storm.animation" to ticks(62),
        "terms.s1" to real(8_700), "terms.s2" to real(7_700), "terms.s3" to real(7_800), "terms.s4" to real(5_000),
        "goldor.leaps" to ticks(9), "goldor.kill" to ticks(85),
        "necron.intro" to ticks(82), "necron.trip1" to ticks(12), "necron.lock1" to ticks(176), "necron.kill" to ticks(155),
    )

    /** A split's dark green (what Pace counts it as before it runs), or a sub split's. */
    fun ref(label: String): Clocks? = SPLIT_REFS[label]
    fun subRef(id: String): Clocks? = SUB_REFS[id]

    private fun ticks(t: Long) = Clocks(t * 50, t)
    private fun real(ms: Long) = Clocks(ms, ms / 50)

    /** A best or band value of [id] (ticks, or ms on a real-time one) on both clocks. */
    fun clocks(id: String, value: Long): Clocks =
        if (SubSplitGrades.clock(id) == SubSplitGrades.Clock.REAL) real(value) else ticks(value)

    /** A sub split as the tracker has it: its id, and its times. */
    data class Sub(val id: String, val split: Split)

    /**
     * The projected finish. [splits] are the run's splits so far (SplitTracker's), [subs] a split's
     * sub splits so far, [target] a split's target (your box or PB), null for its dark green.
     */
    fun pace(splits: List<Split>, subs: (String) -> List<Sub>, now: Stamp, target: (String) -> Clocks? = { null }): Clocks {
        var total = Clocks(0, 0)
        for (label in ORDER) {
            val ref = target(label) ?: SPLIT_REFS[label] ?: continue
            val split = splits.firstOrNull { it.label == label }
            total += when {
                split == null -> ref
                split.stop != null -> length(split, now)
                else -> running(label, split, ref, subs(label), now)
            }
        }
        return total
    }

    /**
     * The running split: its target, moved by what its sub splits have proven against their share
     * of it - a finished one by however much faster or slower it was, the running one once it is
     * past its share. Never less than the split's time so far.
     *
     * A sub split's share: a filler's is its own fixed length; the rest of the target is shared out
     * among the others in proportion to their dark greens. With the dark green as the target that is
     * the dark greens themselves; with a slower PB each share is that much longer, so a run on your
     * PB's pace holds Pace still instead of losing time on every step.
     */
    private fun running(label: String, split: Split, ref: Clocks, subs: List<Sub>, now: Stamp): Clocks {
        val prefix = SUB_PREFIX[label] ?: return atLeast(ref, length(split, now))
        val all = SUB_REFS.filterKeys { it.startsWith(prefix) }
        val fixed = all.filterKeys { it in FIXED_SUBS }.values.fold(Clocks(0, 0), Clocks::plus)
        val varying = all.filterKeys { it !in FIXED_SUBS }.values.fold(Clocks(0, 0), Clocks::plus)
        val kMs = if (varying.ms > 0) (ref.ms - fixed.ms).coerceAtLeast(0).toDouble() / varying.ms else 1.0
        val kTicks = if (varying.ticks > 0) (ref.ticks - fixed.ticks).coerceAtLeast(0).toDouble() / varying.ticks else 1.0
        var est = ref
        for (s in subs) {
            val subRef = SUB_REFS[s.id] ?: continue
            val share = if (s.id in FIXED_SUBS) subRef else Clocks(Math.round(subRef.ms * kMs), Math.round(subRef.ticks * kTicks))
            val len = length(s.split, now)
            val diff = Clocks(len.ms - share.ms, len.ticks - share.ticks)
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
