package com.engineerclient.p3sim.run

/**
 * What the server's ticks actually looked like, recorded from the time of each tick: the evidence that the lag layer makes the
 * freeze-then-burst shape (`/ads dev lag`). Pure: [record] takes the time, so the tests drive it with invented ones.
 *
 * A freeze is a gap between two ticks of at least [FREEZE_MS]; the burst after it is the run of ticks that follow closer together
 * than [BURST_MS] (normal ticks are 50 ms apart, a 2x catch-up 25 ms), within [BURST_WINDOW_MS] of the freeze's end.
 */
class LagStats(private val keep: Int = 100_000) {
    private val times = ArrayList<Long>()

    /** Records that a server tick ran at [nanos] (monotonic). */
    fun record(nanos: Long) { times += nanos; if (times.size > keep) times.subList(0, keep / 10).clear() }

    fun clear() = times.clear()

    val count: Int get() = times.size

    /** One freeze and the burst that followed it. */
    class Freeze(
        val atTick: Int, val gapMs: Double, val missedTicks: Double, val burstTicks: Int, val burstMeanMs: Double,
        /** Ticks the burst ran beyond the normal pace: each burst tick that came `d` ms after the one before counts `1 - d / 50`. */
        val recoveredTicks: Double,
    )

    class Report(
        val ticks: Int, val seconds: Double, val freezes: List<Freeze>,
        /** Ticks the server should have run in [seconds] at 20 a second, minus the ticks it did: the net loss. */
        val lostTicks: Double,
    ) {
        val lostPerMinute: Double get() = if (seconds > 0) lostTicks * 60.0 / seconds else 0.0
        val longestMs: Double get() = freezes.maxOfOrNull { it.gapMs } ?: 0.0
        /** Of the ticks the freezes missed, the share the bursts made up: the data's "recovered within 5 s" over "missed". */
        val recoveredShare: Double get() {
            val missed = freezes.sumOf { it.missedTicks }
            return if (missed <= 0) 0.0 else freezes.sumOf { it.recoveredTicks } / missed
        }
    }

    /** The last [lastSeconds] seconds (all of it when 0), or null with fewer than two ticks. */
    fun report(lastSeconds: Double = 0.0): Report? {
        if (times.size < 2) return null
        val end = times.last()
        var from = 0
        if (lastSeconds > 0) { val cut = end - (lastSeconds * 1e9).toLong(); from = times.indexOfFirst { it >= cut }.coerceAtLeast(0) }
        if (times.size - from < 2) return null
        val freezes = ArrayList<Freeze>()
        var i = from + 1
        while (i < times.size) {
            val gap = (times[i] - times[i - 1]) / 1e6
            if (gap >= FREEZE_MS) {
                var n = 0; var sum = 0.0; var gained = 0.0; var j = i + 1
                while (j < times.size && (times[j] - times[i]) / 1e6 <= BURST_WINDOW_MS) {
                    val d = (times[j] - times[j - 1]) / 1e6
                    if (d >= BURST_MS) break
                    n++; sum += d; gained += 1.0 - d / TICK_MS; j++
                }
                freezes += Freeze(i, gap, gap / TICK_MS - 1, n, if (n > 0) sum / n else 0.0, gained)
                i = j
            } else i++
        }
        val seconds = (times.last() - times[from]) / 1e9
        val ticks = times.size - from - 1
        return Report(ticks, seconds, freezes, seconds * 20.0 - ticks)
    }

    companion object {
        const val FREEZE_MS = 100.0
        const val BURST_MS = 45.0
        const val BURST_WINDOW_MS = 5000.0
        private const val TICK_MS = 50.0
    }
}
