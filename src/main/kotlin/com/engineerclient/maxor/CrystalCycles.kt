package com.engineerclient.maxor

/**
 * Maxor's energy-crystal cycles, timed from what a client can see (docs/mechanics/maxor-alpha.md).
 *
 *  - Maxor's phase runs on a 10-tick check. The pylons open on the check 40 ticks before the
 *    beacon goes in; the beacon slot (73, 221-222, 73) is cleared one tick after that check.
 *  - After a laser hit (on a check) the top crystals come back 40 ticks later.
 *  - A placement starts a 28-tick charge, and the laser fires on the first check at least one
 *    tick after the charge ends: both crystals placed by check - 29 make that check.
 *
 * So each cycle is timed from the moment crystals could first be placed - the pylons opening for
 * the first, the crystals coming back for the rest - and says which check the laser was ready for
 * and which it fired on. Ticks are server ticks; free of Minecraft so it tests headlessly.
 */
class CrystalCycles(
    private val onCycle: (Report) -> Unit,
    private val onPhase: (Phase) -> Unit = {},
) {

    enum class Side { W, E }

    /** What a cycle is timed from. */
    enum class From { PYLONS_OPEN, CRYSTALS_BACK, MAXOR_START }

    /**
     * One cycle. [placed] and [picked] are ticks after [refTick]. [readyFor] and [hitAt] are ticks
     * after the beacon going in (cycle 1) or after the previous hit (later cycles): the check the
     * laser was charged for, and the one it fired on (null if it did not). [best] is the last
     * placement (after [refTick]) that still made the earliest check the cycle could make (the
     * beacon, or the previous hit + 70), [next] the one for the check after.
     */
    data class Report(
        val cycle: Int,
        val from: From,
        val refTick: Int,
        val placed: Map<Side, Int>,
        val placedAt: Map<Side, Int>,
        val picked: List<Pair<String, Int>>,
        val readyFor: Int?,
        val hitAt: Int?,
        val best: Int?,
        val next: Int?,
    )

    /** The whole phase, at Storm's first line: ticks of each step, null where it was not seen. */
    data class Phase(
        val total: Int,
        val firstHitAfterBeacon: Int?,
        val hitGaps: List<Int>,
        val killAfterHit: Int?,
        val stormAfterKill: Int?,
    )

    private class Cycle(val index: Int, val hitBefore: Int?) {
        var back: Int? = null
        val placed = LinkedHashMap<Side, Int>()
        val picked = ArrayList<Pair<String, Int>>()
        var reported = false
    }

    private var start: Int? = null
    private var open: Int? = null
    private var beacon: Int? = null
    private var kill: Int? = null
    private val hits = ArrayList<Int>()
    private val cycles = ArrayList<Cycle>()
    private val current get() = cycles.lastOrNull()

    /** Maxor's first line: a new phase. */
    fun start(tick: Int) {
        start = tick; open = null; beacon = null; kill = null
        hits.clear(); cycles.clear()
        cycles += Cycle(1, null)
    }

    val running get() = start != null

    /** The cycle crystals placed now belong to, and who picked up crystals in it so far. */
    val cycle get() = current?.index ?: 0
    fun pickers(): List<String> = current?.picked?.map { it.first } ?: emptyList()

    /** The beacon slot cleared: the pylons opened on the check the tick before. */
    fun slotCleared(tick: Int) {
        if (open == null && beacon == null) open = tick - 1
    }

    fun beacon(tick: Int) {
        if (beacon == null) beacon = tick
    }

    /** Fresh crystals on the top platforms: after a hit, the next cycle's start. */
    fun crystalsBack(tick: Int) {
        val c = current ?: return
        if (c.index > 1 && c.back == null) c.back = tick
    }

    fun picked(name: String, tick: Int) {
        current?.picked?.add(name to tick)
    }

    fun placed(side: Side, tick: Int) {
        val c = current ?: return
        if (side !in c.placed) c.placed[side] = tick
    }

    /** Maxor became damageable: a laser hit. Flickers within a stun are one hit. */
    fun hit(tick: Int) {
        if (start == null || kill != null) return
        if (hits.isNotEmpty() && tick - hits.last() < 30) return
        current?.let { if (it.placed.isNotEmpty()) report(it, tick) else it.reported = true }
        hits += tick
        cycles += Cycle(cycles.size + 1, tick)
    }

    fun kill(tick: Int) {
        if (kill == null) kill = tick
    }

    /** Storm's first line, or the world going away ([storm] null): reports what is left and ends. */
    fun end(storm: Int?) {
        val s = start ?: return
        current?.let { if (!it.reported && it.placed.isNotEmpty()) report(it, null) }
        if (storm != null) {
            val b = beacon ?: open?.plus(40)
            onPhase(
                Phase(
                    total = storm - s,
                    firstHitAfterBeacon = if (b != null && hits.isNotEmpty()) hits[0] - b else null,
                    hitGaps = hits.zipWithNext { a, c -> c - a },
                    killAfterHit = kill?.let { k -> hits.lastOrNull { it <= k }?.let { k - it } },
                    stormAfterKill = kill?.let { storm - it },
                )
            )
        }
        start = null
    }

    private fun report(c: Cycle, hitTick: Int?) {
        c.reported = true
        val first = c.index == 1
        // The grid the checks fall on, and what the cycle is timed from.
        val anchor: Int
        val ref: Int
        val from: From
        val base: Int?
        if (first) {
            val o = open ?: beacon?.minus(40)
            if (o != null) { anchor = o; ref = o; from = From.PYLONS_OPEN } else { anchor = start!!; ref = start!!; from = From.MAXOR_START }
            base = beacon ?: open?.plus(40)
        } else {
            anchor = c.hitBefore!!
            ref = c.back ?: (anchor + 40)
            from = From.CRYSTALS_BACK
            base = anchor
        }
        // Charged 28 ticks after the later placement, fired on the first check at least a tick after.
        // The first hit also waits for the beacon.
        val ready = if (c.placed.size == 2 && base != null) {
            val check = anchor + ceilTo10(c.placed.values.max() + 29 - anchor)
            maxOf(check, base) - base
        } else null
        // The earliest check this cycle can make, and the latest placement that makes it (and the next).
        val bestCheck = if (first) base else base?.plus(70)
        onCycle(
            Report(
                cycle = c.index,
                from = from,
                refTick = ref,
                placed = c.placed.mapValues { it.value - ref },
                placedAt = HashMap(c.placed),
                picked = if (first) emptyList() else c.picked.map { it.first to it.second - ref },
                readyFor = ready,
                hitAt = if (hitTick != null && base != null) hitTick - base else null,
                best = bestCheck?.let { it - 29 - ref },
                next = bestCheck?.let { it + 10 - 29 - ref },
            )
        )
    }

    private fun ceilTo10(v: Int) = if (v <= 0) 0 else ((v + 9) / 10) * 10
}
